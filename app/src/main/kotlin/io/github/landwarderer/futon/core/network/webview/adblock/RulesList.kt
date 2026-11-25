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
    private val cosmeticRules = ArrayList<CosmeticRule>()

    private data class CosmeticRule(
        val domains: Set<String>,
        val excludedDomains: Set<String>,
        val selector: String,
        val exception: Boolean,
    )

    operator fun get(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String? = null): Rule? {
        if (matchesDomain(url.host, allowDomains) || allowRules.any { it(url, baseUrl, resourceType) }) return null
        if (matchesDomain(url.host, blockDomains)) return Rule.Domain(url.host)
        return blockRules.firstOrNull { it(url, baseUrl, resourceType) }
    }

    fun add(line: String) {
        val normalized = line.trim().lowercase()
        if (normalized.isEmpty() || normalized.startsWith("!") || normalized.startsWith("[")) return
        if (addCosmeticRule(normalized)) return
        val isWhitelist = normalized.startsWith("@@")
        val ruleText = if (isWhitelist) normalized.removePrefix("@@") else normalized
        val pieces = ruleText.split('

    fun trimToSize() {
        blockRules.trimToSize()
        allowRules.trimToSize()
        cosmeticRules.trimToSize()
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
, limit = 2)
        pieces[0].addImpl(isWhitelist, pieces.getOrNull(1))
    }

    /** Returns domain-scoped cosmetic selectors for injection into the current page. */
    fun cosmeticCss(host: String): String {
        val matching = cosmeticRules.filter { rule ->
            (rule.domains.isEmpty() || rule.domains.any { matchesDomain(host, setOf(it)) }) &&
                rule.excludedDomains.none { matchesDomain(host, setOf(it)) }
        }
        val exceptions = matching.asSequence().filter { it.exception }.map { it.selector }.toSet()
        return matching.asSequence()
            .filter { !it.exception && it.selector !in exceptions }
            .map { it.selector }
            .distinct()
            .joinToString(separator = "\n") { selector -> "$selector { display: none !important; }" }
    }

    private fun addCosmeticRule(line: String): Boolean {
        val exceptionIndex = line.indexOf("#@#")
        val normalIndex = line.indexOf("##")
        val markerIndex = when {
            exceptionIndex >= 0 -> exceptionIndex
            normalIndex >= 0 -> normalIndex
            else -> return false
        }
        // Extended CSS operators are intentionally skipped until they can be
        // evaluated safely. Plain CSS selectors cover standard EasyList rules.
        if (line.contains("#?#") || line.contains("#$#")) return true
        val isException = exceptionIndex >= 0
        val markerLength = if (isException) 3 else 2
        val domainPart = line.substring(0, markerIndex)
        val selector = line.substring(markerIndex + markerLength).trim()
        if (selector.isBlank() || selector.length > 500 ||
            selector.contains('{') || selector.contains('}') ||
            selector.contains('<') || selector.contains('>')
        ) return true

        val included = linkedSetOf<String>()
        val excluded = linkedSetOf<String>()
        domainPart.split(',').forEach { raw ->
            val domain = raw.trim()
            if (domain.isBlank()) return@forEach
            if (domain.startsWith("~")) excluded += domain.removePrefix("~")
            else included += domain
        }
        cosmeticRules += CosmeticRule(included, excluded, selector, isException)
        return true
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
