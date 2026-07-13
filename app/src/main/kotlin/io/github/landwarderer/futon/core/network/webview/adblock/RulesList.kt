package io.github.landwarderer.futon.core.network.webview.adblock

import androidx.annotation.CheckResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Very simple implementation of adblock list parser
 * Not all features are supported
 *
 * Performance notes (see AGENTS.md "WebView performance" session):
 * EasyList is ~90k+ lines, and the overwhelming majority are plain
 * `||domain^` rules with no modifiers. Those are bucketed into [blockDomains] /
 * [allowDomains] `HashSet`s for O(1) lookup. Only the minority of rules that need
 * per-request evaluation (path rules, exact-url rules, or domain rules with
 * modifiers such as `$third-party`) fall back to the linear [blockRules] /
 * [allowRules] scan. [registrableDomain] memoizes `HttpUrl.topPrivateDomain()`
 * (a public-suffix-list walk) per host, since the same handful of hosts repeat
 * hundreds of times within a single page load.
 */
class RulesList {

private val blockDomains = HashSet<String>()
private val allowDomains = HashSet<String>()
private val blockRules = ArrayList<Rule>()
private val allowRules = ArrayList<Rule>()

// host -> registrable domain. Bounded by the number of distinct hosts seen
// during this WebView session, never cleared for the lifetime of the rule set.
private val domainCache = ConcurrentHashMap<String, String>()

operator fun get(url: HttpUrl, baseUrl: HttpUrl?): Rule? {
val domain = registrableDomain(url)

// Two-level check: exact registrable domain first (handles the vast
// majority of blocklist entries in O(1)), then fall back to the slower
// per-rule scan for path/exact-url/modifier rules.
val rule: Rule = if (domain in blockDomains) {
Rule.Domain(domain)
} else {
blockRules.find { x -> x(url, baseUrl) } ?: return null
}

val isAllowed = domain in allowDomains || allowRules.any { x -> x(url, baseUrl) }
return rule.takeUnless { isAllowed }
}

fun add(line: String) {
val parts = line.lowercase().trim().split('$')
parts.first().addImpl(isWhitelist = false, modifiers = parts.getOrNull(1))
}

fun trimToSize() {
blockRules.trimToSize()
allowRules.trimToSize()
}

/** Same domain string appears in hundreds of requests per page load — cache it. */
private fun registrableDomain(url: HttpUrl): String =
domainCache.getOrPut(url.host) { url.topPrivateDomain() ?: url.host }

private fun String.addImpl(isWhitelist: Boolean, modifiers: String?) {
when {
startsWith('!') || startsWith('[') -> {
// Comment, do nothing
}

startsWith("||") -> {
val domain = substring(2).substringBefore('^').trim()
if (modifiers.isNullOrEmpty()) {
// Fast path: plain domain rule, no modifiers to evaluate.
(if (isWhitelist) allowDomains else blockDomains) += domain
} else {
val list = if (isWhitelist) allowRules else blockRules
list += Rule.Domain(domain).withModifiers(modifiers)
}
}

startsWith('|') -> {
val url = substring(1).substringBefore('^').trim().toHttpUrlOrNull()
if (url != null) {
val list = if (isWhitelist) allowRules else blockRules
list += Rule.ExactUrl(url).withModifiers(modifiers)
}
}

startsWith("@@") -> {
substring(2).substringBefore('^').trim().addImpl(!isWhitelist, modifiers)
}

startsWith("##") -> {
// TODO css rules
}

else -> {
val list = if (isWhitelist) allowRules else blockRules
if (endsWith('*')) {
list += Rule.Path(this.dropLast(1), contains = true).withModifiers(modifiers)
} else if (!contains('*')) { // wildcards is not supported yet
list += Rule.Path(this, contains = false).withModifiers(modifiers)
}
}
}
}

@CheckResult
private fun Rule.withModifiers(options: String?): Rule {
if (options.isNullOrEmpty()) {
return this
}
var script: Boolean? = null
var thirdParty: Boolean? = null
options.split(',').forEach {
val isNot = it.startsWith('~')
when (it.removePrefix("~")) {
"script" -> script = !isNot
"third-party" -> thirdParty = !isNot
}
}
return Rule.WithModifiers(
baseRule = this,
script = script,
thirdParty = thirdParty,
domains = null, //TODO
domainsNot = null, //TODO
)
}
}
