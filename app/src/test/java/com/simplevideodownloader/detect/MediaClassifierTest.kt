package com.simplevideodownloader.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierTest {
    @Test
    fun classifiesByExtension() {
        assertEquals(MediaKind.VIDEO, MediaClassifier.classify("https://cdn.x.com/v/clip.MP4?token=1"))
        assertEquals(MediaKind.HLS, MediaClassifier.classify("https://cdn.x.com/hls/master.m3u8"))
        assertEquals(MediaKind.DASH, MediaClassifier.classify("https://cdn.x.com/d/manifest.mpd"))
        assertEquals(MediaKind.AUDIO, MediaClassifier.classify("https://cdn.x.com/a/song.mp3"))
        assertNull(MediaClassifier.classify("https://cdn.x.com/hls/seg-001.ts"))
        assertNull(MediaClassifier.classify("https://cdn.x.com/app.js"))
        assertNull(MediaClassifier.classify("blob:https://x.com/123"))
    }

    @Test
    fun contentTypeWins() {
        assertEquals(MediaKind.HLS, MediaClassifier.classify("https://x.com/play?id=1", "application/vnd.apple.mpegurl"))
        assertEquals(MediaKind.VIDEO, MediaClassifier.classify("https://x.com/get", "video/mp4; charset=binary"))
        assertNull(MediaClassifier.classify("https://x.com/seg", "video/mp2t"))
    }

    @Test
    fun probing() {
        assertTrue(MediaClassifier.shouldProbe("https://x.com/video/stream?id=99"))
        assertFalse(MediaClassifier.shouldProbe("https://x.com/video/thumb.jpg"))
        assertFalse(MediaClassifier.shouldProbe("https://x.com/api/user"))
    }

    @Test
    fun dedupeIgnoresRangeParams() {
        assertEquals(
            MediaClassifier.dedupeKey("https://x.com/v.mp4?id=1"),
            MediaClassifier.dedupeKey("https://x.com/v.mp4?id=1&range=0-1000"),
        )
    }

    @Test
    fun heightFromUrl() {
        assertEquals(720, MediaClassifier.heightFromUrl("https://cdn.x.com/v/clip_720p.mp4"))
        assertEquals(1080, MediaClassifier.heightFromUrl("https://cdn.x.com/1920x1080/clip.mp4"))
        assertEquals(480, MediaClassifier.heightFromUrl("https://cdn.x.com/v/480P/index.m3u8"))
        assertNull(MediaClassifier.heightFromUrl("https://cdn.x.com/v/12345678.mp4"))
        assertNull(MediaClassifier.heightFromUrl("https://cdn.x.com/v/1500px.jpg"))
    }

    @Test
    fun looksLikeAd() {
        assertTrue(MediaClassifier.looksLikeAd("https://cdn.site.com/ads/preroll_15s.mp4"))
        assertTrue(MediaClassifier.looksLikeAd("https://cdn.site.com/vast/creative.mp4"))
        assertTrue(MediaClassifier.looksLikeAd("https://s0.2mdn.doubleclick.net/videoplayback/x.mp4"))
        assertFalse(MediaClassifier.looksLikeAd("https://cdn.site.com/uploads/2024/video.mp4"))
        assertFalse(MediaClassifier.looksLikeAd("https://cdn.site.com/headshot/clip_720p.mp4"))
        assertFalse(MediaClassifier.looksLikeAd("https://ads.example.com/"))
    }

    @Test
    fun isGifLike() {
        assertTrue(MediaClassifier.isGifLike(300, 200, loop = true, muted = true, controls = false, durationSec = -1.0))
        assertTrue(MediaClassifier.isGifLike(120, 90, loop = false, muted = false, controls = true, durationSec = 60.0))
        assertTrue(MediaClassifier.isGifLike(0, 0, loop = false, muted = false, controls = true, durationSec = 3.0))
        assertFalse(MediaClassifier.isGifLike(640, 360, loop = false, muted = false, controls = true, durationSec = 120.0))
        assertFalse(MediaClassifier.isGifLike(0, 0, loop = false, muted = true, controls = false, durationSec = -1.0))
    }
}
