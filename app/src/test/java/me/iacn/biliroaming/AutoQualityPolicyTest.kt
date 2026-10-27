package me.iacn.biliroaming

import me.iacn.biliroaming.utils.AutoQualityPolicy as Q
import org.junit.Assert.*
import org.junit.Test

class AutoQualityPolicyTest {
    private val tracks = listOf(Q.Video(80, 1920, 1080, 30.0), Q.Video(112, 1920, 1080, 30.0),
        Q.Video(116, 1920, 1080, 60.0), Q.Video(64, 1280, 720, 30.0))

    @Test fun highestFrameRateWithinHighestResolution() {
        assertEquals(116, Q.video(tracks, "highest", "highest", true))
        assertEquals(120, Q.video(tracks + Q.Video(120, 3840, 2160, 30.0), "highest", "highest", true))
    }
    @Test fun missingPreferredUsesAvailableFallback() {
        assertEquals(116, Q.video(tracks, "127", "116", true))
        assertEquals(80, Q.video(tracks, "80", "highest", true))
        assertNull(Q.video(emptyList(), "127", "highest", true))
    }
    @Test fun approximateSixtyFpsRetainsHdrTier() {
        val tracks = listOf(Q.Video(125, 3840, 2160, 58.824), Q.Video(120, 3840, 2160, 60.0))
        assertEquals(125, Q.video(tracks, "highest", "highest", true))
    }
    @Test fun sdrDefaultSkipsHdrAndDolbyEvenForPreferredOrFallback() {
        val tracks = listOf(Q.Video(125, 3840, 2160, 120.0), Q.Video(126, 3840, 2160, 60.0),
            Q.Video(120, 3840, 2160, 60.0), Q.Video(116, 1920, 1080, 60.0))
        assertEquals(120, Q.video(tracks, "highest", "highest", true, allowHdr=false, allowDolby=false))
        assertEquals(120, Q.video(tracks, "125", "126", true, allowHdr=false, allowDolby=false))
        assertEquals(127, Q.video(tracks + Q.Video(127, 7680, 4320, 30.0), "highest", "highest", true,
            allowHdr=false, allowDolby=false))
    }
    @Test fun rationalFrameRatesAndInvalidDenominator() {
        assertEquals(59.940, Q.frameRate("60000/1001"), .001)
        assertEquals(0.0, Q.frameRate("60/0"), .0)
        assertEquals(0.0, Q.frameRate(null), .0)
    }
    @Test fun audioHonorsBothSwitchesAndFallsBack() {
        val tracks = listOf(Q.Audio(30280, "NORMAL", 320000), Q.Audio(30351, "HIRES", 1000000),
            Q.Audio(30250, "DOLBY", 448000))
        assertEquals(30250, Q.audio(tracks, true, true)?.id)
        assertEquals(30351, Q.audio(tracks, true, false)?.id)
        assertEquals(30280, Q.audio(tracks, false, false)?.id)
        assertEquals(30280, Q.audio(tracks.take(1), true, true)?.id)
    }
}
