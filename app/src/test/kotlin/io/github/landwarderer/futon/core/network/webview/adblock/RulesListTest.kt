package io.github.landwarderer.futon.core.network.webview.adblock

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RulesListTest {

    @Test
    fun blocksDomainAndSubdomains() {
        val rules = RulesList().apply { add("||ads.example.com^") }
        assertNotNull(rules["https://ads.example.com/banner.js".toHttpUrl(), "https://reader.example.org".toHttpUrl()])
        assertNotNull(rules["https://cdn.ads.example.com/banner.js".toHttpUrl(), "https://reader.example.org".toHttpUrl()])
        assertNull(rules["https://good.example.com/page".toHttpUrl(), "https://reader.example.org".toHttpUrl()])
    }

    @Test
    fun allowExceptionOverridesBlockRule() {
        val rules = RulesList().apply {
            add("||cdn.example.com^")
            add("@@||cdn.example.com^")
        }
        assertNull(rules["https://cdn.example.com/page.jpg".toHttpUrl(), "https://reader.example.org".toHttpUrl()])
    }

    @Test
    fun respectsResourceTypeModifier() {
        val rules = RulesList().apply { add("||ads.example.com^\$script") }
        val url = "https://ads.example.com/creative.js".toHttpUrl()
        val page = "https://reader.example.org".toHttpUrl()
        assertNotNull(rules[url, page, "script"])
        assertNull(rules[url, page, "image"])
    }

    @Test
    fun respectsThirdPartyModifier() {
        val rules = RulesList().apply { add("||tracker.example.net^\$third-party") }
        val url = "https://tracker.example.net/pixel".toHttpUrl()
        assertNotNull(rules[url, "https://reader.example.org".toHttpUrl()])
        assertNull(rules[url, "https://tracker.example.net/article".toHttpUrl()])
    }

    @Test
    fun unsupportedModifiersDoNotBecomeBroadBlockingRules() {
        val rules = RulesList().apply { add("||ads.example.com^\$redirect=noopjs") }
        assertNull(
            rules[
                "https://ads.example.com/creative.js".toHttpUrl(),
                "https://reader.example.org".toHttpUrl(),
            ]
        )
    }

    @Test
    fun cosmeticRulesRespectDomainAndExceptionScopes() {
        val rules = RulesList().apply {
            add("reader.example.org##.ad-banner")
            add("reader.example.org##.sponsored")
            add("reader.example.org#@#.ad-banner")
            add("other.example.org##.other-ad")
        }
        val css = rules.cosmeticCss("reader.example.org")
        org.junit.Assert.assertFalse(css.contains(".ad-banner"))
        org.junit.Assert.assertTrue(css.contains(".sponsored"))
        org.junit.Assert.assertFalse(css.contains(".other-ad"))
    }

    @Test
    fun respectsDomainScopeOnException() {
        val rules = RulesList().apply {
            add("||cdn.example.com^")
            add("@@||cdn.example.com^\$domain=reader.example.org")
        }
        val url = "https://cdn.example.com/image.jpg".toHttpUrl()
        assertNull(rules[url, "https://reader.example.org".toHttpUrl()])
        assertNotNull(rules[url, "https://other.example.org".toHttpUrl()])
    }
}
