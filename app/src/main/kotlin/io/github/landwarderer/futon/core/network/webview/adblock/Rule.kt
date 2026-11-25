package io.github.landwarderer.futon.core.network.webview.adblock

import okhttp3.HttpUrl

sealed interface Rule {
    operator fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String? = null): Boolean

    data object Never : Rule {
        override fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String?): Boolean = false
    }

    data class Domain(private val domain: String) : Rule {
        override fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String?): Boolean =
            url.host == domain || url.host.endsWith(".$domain")
    }

    data class ExactUrl(private val url: HttpUrl) : Rule {
        override fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String?): Boolean = url == this.url
    }

    data class Path(private val path: String, private val contains: Boolean) : Rule {
        override fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String?): Boolean {
            val fullPath = url.host + url.encodedPath
            return if (contains) fullPath.contains(path) else fullPath.endsWith(path)
        }
    }

    data class WithModifiers(
        private val baseRule: Rule,
        private val thirdParty: Boolean?,
        private val includedTypes: Set<String>,
        private val excludedTypes: Set<String>,
        private val domains: Set<String>,
        private val domainsNot: Set<String>,
    ) : Rule {
        override fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: String?): Boolean {
            if (!baseRule(url, baseUrl, resourceType)) return false
            if (resourceType != null) {
                if (includedTypes.isNotEmpty() && resourceType !in includedTypes) return false
                if (resourceType in excludedTypes) return false
            } else if (includedTypes.isNotEmpty()) return false

            if (baseUrl != null) {
                if (thirdParty != null) {
                    // PublicSuffixDatabase may be unavailable in local JVM tests or a damaged
                    // runtime. Fall back to host comparison instead of failing the request.
                    val requestSite = runCatching { url.topPrivateDomain() }.getOrNull() ?: url.host
                    val pageSite = runCatching { baseUrl.topPrivateDomain() }.getOrNull() ?: baseUrl.host
                    if ((requestSite != pageSite) != thirdParty) return false
                }
                if (domains.isNotEmpty() && domains.none { baseUrl.host == it || baseUrl.host.endsWith(".$it") }) return false
                if (domainsNot.any { baseUrl.host == it || baseUrl.host.endsWith(".$it") }) return false
            } else if (domains.isNotEmpty() || domainsNot.isNotEmpty()) return false
            return true
        }
    }
}
