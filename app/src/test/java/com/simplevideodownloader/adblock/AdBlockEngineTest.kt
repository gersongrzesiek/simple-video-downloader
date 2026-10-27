package com.simplevideodownloader.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockEngineTest {

    private val engine = AdBlockEngine.Builder().addText(
        """
        [Adblock Plus 2.0]
        ! Comentario
        ||ads.example.com^
        ||tracker.net^${'$'}third-party
        /banners/*${'$'}image
        ||cdn.example.org/ads/*.js
        @@||ads.example.com/allowed/*
        ||adserver.io^${'$'}important
        @@||adserver.io^
        ||popups.io^${'$'}popup
        /promo-video.${'$'}domain=news.com|~sport.news.com
        ||cdn.redirect.io^${'$'}redirect=noopjs
        0.0.0.0 hosts-ad.com
        @@||safe-site.com^${'$'}document
        ##.ad-banner
        ##.sponsored
        example.com##.example-only
        ~shop.example.com##.not-in-shop
        example.com#@#.sponsored
        google.*##.wildcard-domain
        example.com##div:has-text(Patrocinado)
        @@||nohide.com^${'$'}elemhide
        """.trimIndent()
    ).build()

    private fun blocked(url: String, page: String? = "https://site.com/", type: ResourceType = ResourceType.SCRIPT) =
        engine.shouldBlock(url, page, type).blocked

    @Test
    fun blocksHostRulesAndSubdomains() {
        assertTrue(blocked("https://ads.example.com/x.js"))
        assertTrue(blocked("https://img.ads.example.com/x.js"))
        assertFalse(blocked("https://notads.example.com/x.js"))
        assertTrue(blocked("http://hosts-ad.com/a"))
    }

    @Test
    fun exceptionsOverrideUnlessImportant() {
        assertFalse(blocked("https://ads.example.com/allowed/x.js"))
        assertTrue(blocked("https://adserver.io/x.js"))
    }

    @Test
    fun thirdPartyOption() {
        assertTrue(blocked("https://tracker.net/p.gif", "https://site.com/"))
        assertFalse(blocked("https://tracker.net/p.gif", "https://www.tracker.net/"))
    }

    @Test
    fun resourceTypeOption() {
        assertTrue(blocked("https://cdn.site.com/banners/top.png", type = ResourceType.IMAGE))
        assertFalse(blocked("https://cdn.site.com/banners/top.png", type = ResourceType.SCRIPT))
    }

    @Test
    fun wildcardPatterns() {
        assertTrue(blocked("https://cdn.example.org/ads/v2/loader.js"))
        assertFalse(blocked("https://cdn.example.org/static/loader.js"))
    }

    @Test
    fun domainOption() {
        assertTrue(blocked("https://cdn.x.com/promo-video.mp4", "https://news.com/a", ResourceType.MEDIA))
        assertFalse(blocked("https://cdn.x.com/promo-video.mp4", "https://sport.news.com/a", ResourceType.MEDIA))
        assertFalse(blocked("https://cdn.x.com/promo-video.mp4", "https://other.com/a", ResourceType.MEDIA))
    }

    @Test
    fun popupsAndDocumentsNeedExplicitType() {
        assertTrue(blocked("https://popups.io/x", type = ResourceType.POPUP))
        assertFalse(blocked("https://popups.io/x", type = ResourceType.SCRIPT))
        assertFalse(blocked("https://ads.example.com/", null, ResourceType.DOCUMENT))
    }

    @Test
    fun unsupportedOptionsAreIgnored() {
        assertFalse(blocked("https://cdn.redirect.io/x.js"))
    }

    @Test
    fun documentAllowlist() {
        assertTrue(engine.isPageAllowed("https://www.safe-site.com/page"))
        assertFalse(engine.isPageAllowed("https://site.com/page"))
    }

    @Test
    fun cosmeticRules() {
        val css = engine.cssFor("www.example.com")
        assertTrue(css.contains(".ad-banner{"))
        assertTrue(css.contains(".example-only{"))
        assertTrue(css.contains(".not-in-shop{"))
        assertFalse(css.contains(".sponsored{"))
        assertFalse(css.contains("has-text"))
        assertFalse(css.contains(".wildcard-domain"))

        val shop = engine.cssFor("shop.example.com")
        assertFalse(shop.contains(".not-in-shop{"))

        val other = engine.cssFor("other.org")
        assertTrue(other.contains(".sponsored{"))
        assertFalse(other.contains(".example-only{"))

        assertEquals("", engine.cssFor("nohide.com"))
    }

    @Test
    fun tokensOnlyIncludeBoundedWords() {
        assertEquals(listOf("ads"), FilterParser.tokensOf("||cdn.example.org/ads/*.js").filter { it == "ads" })
        assertFalse(FilterParser.tokensOf("banner*").contains("banner"))
        assertFalse(FilterParser.tokensOf("adsbygoogle").contains("adsbygoogle"))
    }

    @Test
    fun siteCalculation() {
        assertEquals("example.com", DomainUtils.siteOf("a.b.example.com"))
        assertEquals("clarin.com.ar", DomainUtils.siteOf("www.clarin.com.ar"))
        assertEquals("example.com", DomainUtils.hostOf("https://user@Example.com:8080/x?y"))
    }
}
