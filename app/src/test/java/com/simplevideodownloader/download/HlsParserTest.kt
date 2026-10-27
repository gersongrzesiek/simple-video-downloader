package com.simplevideodownloader.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {
    @Test
    fun parsesMasterSortedByBandwidth() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
            https://other.cdn/720/index.m3u8
        """.trimIndent()
        val master = HlsParser.parse(text, "https://cdn.x.com/v/master.m3u8") as HlsPlaylist.Master
        assertEquals(2, master.variants.size)
        assertEquals("https://other.cdn/720/index.m3u8", master.variants[0].url)
        assertEquals("https://cdn.x.com/v/360/index.m3u8", master.variants[1].url)
        assertTrue(master.variants[0].label.startsWith("720p"))
    }

    @Test
    fun parsesMediaWithKeyAndMap() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:6.0,
            seg10.m4s
            #EXTINF:6.0,
            seg11.m4s
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            seg12.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parse(text, "https://cdn.x.com/v/720/index.m3u8") as HlsPlaylist.Media
        assertEquals(3, media.segments.size)
        assertEquals(10L, media.segments[0].sequence)
        assertEquals("https://cdn.x.com/v/720/seg11.m4s", media.segments[1].url)
        assertEquals("https://cdn.x.com/v/720/key.bin", media.segments[0].key!!.uri)
        assertEquals(16, media.segments[0].key!!.iv!!.size)
        assertEquals(null, media.segments[2].key)
        assertNotNull(media.initSegment)
        assertFalse(media.isLive)
        assertFalse(media.isEncryptedUnsupported)
    }

    @Test
    fun byteRanges() {
        val text = "#EXTM3U\n#EXT-X-BYTERANGE:100@0\n#EXTINF:1,\nall.ts\n#EXT-X-BYTERANGE:50\n#EXTINF:1,\nall.ts\n"
        val media = HlsParser.parse(text, "https://x.com/a.m3u8") as HlsPlaylist.Media
        assertEquals(0L..99L, media.segments[0].byteRange)
        assertEquals(100L..149L, media.segments[1].byteRange)
        assertTrue(media.isLive)
    }
}
