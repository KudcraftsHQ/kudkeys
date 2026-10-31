package com.kudcrafts.kudkeys.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {
    @Test fun ordersBySegment_partialReplacesAndYieldsToFinal() {
        val t = Transcript()
        t.partial(0, "ha"); assertEquals("ha", t.text)
        t.partial(0, "halo ak"); assertEquals("halo ak", t.text)
        t.final(3, "nanti."); assertEquals("halo ak nanti.", t.text)
        t.final(0, "Halo, aku otw."); assertEquals("Halo, aku otw. nanti.", t.text)
        t.partial(0, "stale"); assertEquals("Halo, aku otw. nanti.", t.text)
        t.final(1, "  "); assertEquals("Halo, aku otw. nanti.", t.text)
    }

    @Test fun pcmLevel_silenceIsZero_fullScaleIsOne() {
        assertEquals(0f, pcmLevel(ByteArray(3200), 3200))
        val loud = ByteArray(3200) { if (it % 2 == 0) 0xff.toByte() else 0x7f }
        assertEquals(1f, pcmLevel(loud, 3200), 0.01f)
    }
}
