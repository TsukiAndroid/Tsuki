package io.github.landwarderer.futon.core.network.webview.adblock

import androidx.annotation.CheckResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Fast-path matcher for common EasyList/EasyPrivacy network rules. */
class RulesList {
    private val blockDomains = HashSet<String>()
    private val allowDomains = HashSet<String>()
    private val blockRules = ArrayList<Rule>()
    private val allowRules = ArrayList<Rule>()

    operator fun get(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String? = null): Rule? {
        if (matchesDomain(url.host, allowDomains) || allowRules.any { it(url, baseUrl, resourceType) }) return null
        if (matchesDomain(url.host, blockDomains)) return Rule.Domain(url.host)
        return blockRules.firstOrNull { it(url, baseUrl, resourceType) }
    }

    fun add(line: String) {
        val normalized = line.trim().lowercase()
        if (normalized.isEmpty() || normalized.startsWith("!") || normalized.startsWith("[")) return
        val isWhitelist = normalized.startsWith("@@")
        val ruleText = if (isWhitelist) normalized.removePrefix("@@") else normalized
        val pieces = ruleText.split('$', limit = 2)
        pieces[0].addImpl(isWhitelist, pieces.getOrNull(1))
    }

    fun trimToSize() {
        blockRules.trimToSize()
        allowRules.trimToSize()
    }

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
        val targetDomains = if (isWhitelist) allowDomains else blockDomains
        val targetRules = if (isWhitelist) allowRules else blockRules
        when {
            startsWith("||") -> {
                val domain = substring(2).takeWhile { it != '^' && it != '/' && it != '*' && it != '?' && it != '|' }
                if (domain.isNotBlank() && domain.matches(Regex("[a-z0-9.-]+"))) {
                    if (modifiers.isNullOrBlank()) targetDomains += domain
                    else targetRules += Rule.Domain(domain).withModifiers(modifiers)
                }
            }
            startsWith("|") -> {
                val exact = removePrefix("|").removeSuffix("|").substringBefore('^')
                val url = exact.toHttpUrlOrNull()
                if (url != null) targetRules += Rule.ExactUrl(url).withModifiers(modifiers)
            }
            startsWith("##") || contains("##") || contains("#@#") -> Unit
            else -> {
                if (contains("*") && !endsWith("*")) return
                if (contains("*")) {
                    val prefix = removeSuffix("*")
                    if (prefix.isNotBlank()) targetRules += Rule.Path(prefix, contains = true).withModifiers(modifiers)
                } else if (isNotBlank()) {
                    targetRules += Rule.Path(this, contains = false).withModifiers(modifiers)
                }
            }
        }
    }

    @CheckResult
    private fun Rule.withModifiers(options: String?): Rule {
        if (options.isNullOrBlank()) return this
        var thirdParty: Boolean? = null
        val includedTypes = linkedSetOf<String>()
        val excludedTypes = linkedSetOf<String>()
        val domains = linkedSetOf<String>()
        val domainsNot = linkedSetOf<String>()
        val supportedTypes = setOf("document", "subdocument", "script", "image", "stylesheet", "font", "media", "xmlhttprequest", "websocket", "other")

        options.split(',').forEach { raw ->
            val option = raw.trim()
            if (option.isEmpty()) return@forEach
            val negated = option.startsWith("~")
            val value = option.removePrefix("~")
            when {
                value == "third-party" -> thirdParty = !negated
                value.startsWith("domain=") -> value.removePrefix("domain=").split('|').forEach { domain ->
                    if (domain.isNotBlank()) {
                        if (domain.startsWith("~")) domainsNot += domain.removePrefix("~")
                        else domains += domain
                    }
                }
                value in supportedTypes -> if (negated) excludedTypes += value else includedTypes += value
            }
        }
        return Rule.WithModifiers(this, thirdParty, includedTypes, excludedTypes, domains, domainsNot)
    }
}
