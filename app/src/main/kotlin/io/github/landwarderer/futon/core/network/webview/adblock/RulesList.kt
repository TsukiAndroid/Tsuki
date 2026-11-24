package io.github.landwarderer.futon.core.network.webview.adblock

import androidx.annotation.CheckResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
 * [allowRules] scan. Plain domain checks walk the host's suffixes, so rules
 * targeting a subdomain are not lost by registrable-domain normalisation.
 */
class RulesList {

private val blockDomains = HashSet<String>()
private val allowDomains = HashSet<String>()
private val blockRules = ArrayList<Rule>()
private val allowRules = ArrayList<Rule>()

operator fun get(url: HttpUrl, baseUrl: HttpUrl?): Rule? {
// Plain domain rules may target a subdomain (for example ||ads.site.com^),
// so compare every host suffix rather than only the registrable domain.
 // The number of suffixes is bounded by the host's label count.
val domainBlocked = matchesDomain(url.host, blockDomains)
val rule: Rule = if (domainBlocked) {
Rule.Domain(url.host)
} else {
blockRules.find { x -> x(url, baseUrl) } ?: return null
}

val isAllowed = matchesDomain(url.host, allowDomains) || allowRules.any { x -> x(url, baseUrl) }
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

/** Match a rule against the host itself or any parent-domain suffix. */
private fun matchesDomain(host: String, domains: Set<String>): Boolean {
var candidate = host
while (true) {
if (candidate in domains) return true
val dot = candidate.indexOf('.')
if (dot < 0) return false
candidate = candidate.substring(dot + 1)
}
}

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
