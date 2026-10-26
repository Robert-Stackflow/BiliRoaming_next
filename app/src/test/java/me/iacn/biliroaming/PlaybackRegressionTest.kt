package me.iacn.biliroaming

import me.iacn.biliroaming.hook.kotlinx.KotlinxSplashListProcessor
import me.iacn.biliroaming.utils.InitialQualityPolicy
import org.junit.Assert.*
import org.junit.Test

class PlaybackRegressionTest {
    @Test fun firstRequestAndManualSelection() {
        val policy = InitialQualityPolicy()
        assertTrue(policy.shouldOverride(1, 64, 0))
        assertFalse(policy.shouldOverride(1, 32, 0))
        assertTrue(policy.shouldOverride(1, 0, 0))
    }

    @Test fun concurrentPreloadDoesNotReplaceManualChoice() {
        val policy = InitialQualityPolicy()
        assertTrue(policy.shouldOverride(1, 64, 0))
        assertTrue(policy.shouldOverride(2, 64, 0))
        assertFalse(policy.shouldOverride(1, 32, 0))
    }

    @Test fun downloadsDoNotConsumeFirstPlayback() {
        val policy = InitialQualityPolicy()
        assertFalse(policy.shouldOverride(1, 64, 1))
        assertFalse(policy.shouldOverride(0, 64, 0))
        assertTrue(policy.shouldOverride(1, 64, 0))
    }

    class SplashResponse {
        @JvmField var items: List<String> = listOf("cached-ad")
        @JvmField var requestId: String = "keep"
    }

    @Test fun splashUpdateCanAppendAfterFiltering() {
        val first = SplashResponse()
        val second = SplashResponse()
        val processor = KotlinxSplashListProcessor()
        processor.process(first)
        processor.process(second)
        assertTrue(first.items.isEmpty())
        // 9.13 SplashUpdateComponent appends to this collection during cold start.
        (first.items as MutableList<String>).addAll(listOf("next-update"))
        assertEquals(listOf("next-update"), first.items)
        assertTrue(second.items.isEmpty())
        assertEquals("keep", first.requestId)
    }
}
