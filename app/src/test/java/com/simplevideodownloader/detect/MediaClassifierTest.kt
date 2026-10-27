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
}
