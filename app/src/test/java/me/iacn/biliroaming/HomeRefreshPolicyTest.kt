package me.iacn.biliroaming

import com.google.gson.annotations.SerializedName
import me.iacn.biliroaming.utils.HomeRefreshPolicy
import me.iacn.biliroaming.utils.HomeMenuPolicy
import org.junit.Assert.*
import org.junit.Test

class HomeRefreshPolicyTest {
    private class Config {
        @field:SerializedName("auto_refresh_time_by_active") var a = 1200L
        @field:SerializedName("auto_refresh_time_by_appear") var b = 1200L
        @field:SerializedName("auto_refresh_time_by_behavior") var c = 60
        @field:SerializedName("auto_refresh_by_behavior") var d = 7
        @field:SerializedName("is_back_auto_refresh") var e = 1
        @field:SerializedName("top_refresh_latest_exp") var f = 2
        @field:SerializedName("refresh_min_interval") var g = 10
    }

    @Test fun obfuscatedConfigDisablesAllAutomaticTriggersAndKeepsUserRefresh() {
        val config = Config()
        assertEquals(5, HomeRefreshPolicy.disable(config))
        assertEquals(-1L, config.a)
        assertEquals(-1L, config.b)
        assertEquals(-1, config.c)
        assertEquals(0, config.d)
        assertEquals(0, config.e)
        assertEquals(2, config.f)
        assertEquals(10, config.g)
    }

    private open class Legacy {
        var autoRefreshTimeByActive = 800L
    }
    private class LegacyChild : Legacy() {
        var autoRefreshTimeByBehavior = 60
        var auto_refresh_time = 1200
    }

    @Test fun legacyAndInheritedFieldsStillWork() {
        val config = LegacyChild()
        assertEquals(3, HomeRefreshPolicy.disable(config))
        assertEquals(-1L, config.autoRefreshTimeByActive)
        assertEquals(-1, config.autoRefreshTimeByBehavior)
        assertEquals(0, config.auto_refresh_time)
        assertEquals(0, HomeRefreshPolicy.disable(Any()))
    }

    @Test fun cachedAutomaticReasonsAreBlockedAndUserActionsRemainAvailable() {
        listOf("AUTO_BACK_FROM_BACKGROUND", "AUTO_BACK_FROM_OTHER_PAGE", "AUTO_BACK_FROM_BEHAVIOR",
            "FLUSH_ON_BACK_PRESS").forEach { assertTrue(it, HomeRefreshPolicy.blocks(it)) }
        listOf("NORMAL", "PULL_DOWN", "PULL_UP", "TAB_CLICK", "TAB_DOUBLE_CLICK",
            "TOP_REFRESH_BUTTON_CLICK", "LOGIN_CHANGE", "SWITCH_RECOMMEND_MODE_FROM_SETTINGS",
            "FLUSH_TAB_SWITCH_COLUMN", "UNKNOWN").forEach { assertFalse(it, HomeRefreshPolicy.blocks(it)) }
        assertFalse(HomeRefreshPolicy.blocks(null))
    }

    @Test fun bothServerAndBuiltInGameRoutesAreRemoved() {
        listOf("bilibili://game_center/home", "bilibili://game_center/home/menu?source=home",
            "action://game_center/home/menu", "action://game_center/home/menu?badge=1")
            .forEach { assertTrue(it, HomeMenuPolicy.isGameCenter(it)) }
        listOf("bilibili://message/home", "action://game_center/other/menu", "bilibili://game_center/homepage")
            .forEach { assertFalse(it, HomeMenuPolicy.isGameCenter(it)) }
        assertFalse(HomeMenuPolicy.isGameCenter(null))
    }
}
