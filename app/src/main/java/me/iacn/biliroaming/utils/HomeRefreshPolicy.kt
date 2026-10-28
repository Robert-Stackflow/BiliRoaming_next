package me.iacn.biliroaming.utils

import java.lang.reflect.Field

/** Wire names survive R8, unlike the Kotlin property names of the new feed. */
object HomeRefreshPolicy {
    private val values = mapOf(
        "auto_refresh_time_by_active" to -1L, "autoRefreshTimeByActive" to -1L,
        "auto_refresh_time_by_appear" to -1L, "autoRefreshTimeByAppear" to -1L,
        "auto_refresh_time_by_behavior" to -1L, "autoRefreshTimeByBehavior" to -1L,
        "auto_refresh_by_behavior" to 0L, "autoRefreshByBehavior" to 0L,
        "is_back_auto_refresh" to 0L,
        "auto_refresh_time" to 0L,
    )

    fun serializedName(field: Field): String? = field.annotations.firstNotNullOfOrNull { annotation ->
        val type = annotation.annotationClass.java
        val method = when (type.name) {
            "com.google.gson.annotations.SerializedName" -> "value"
            "com.alibaba.fastjson.annotation.JSONField" -> "name"
            else -> return@firstNotNullOfOrNull null
        }
        runCatching { type.getMethod(method).invoke(annotation) as? String }.getOrNull()
    }

    fun disable(config: Any): Int {
        var changed = 0
        generateSequence(config.javaClass as Class<*>?) { it.superclass }.forEach { type ->
            type.declaredFields.forEach fieldLoop@{ field ->
                val value = values[serializedName(field)] ?: values[field.name] ?: return@fieldLoop
                runCatching {
                    field.isAccessible = true
                    when (field.type) {
                        java.lang.Long.TYPE, java.lang.Long::class.java -> field.set(config, value)
                        java.lang.Integer.TYPE, java.lang.Integer::class.java -> field.set(config, value.toInt())
                        else -> return@runCatching
                    }
                    changed++
                }
            }
        }
        return changed
    }

    fun blocks(reason: String?) = reason in setOf(
        "AUTO_BACK_FROM_BACKGROUND", "AUTO_BACK_FROM_OTHER_PAGE",
        "AUTO_BACK_FROM_BEHAVIOR", "FLUSH_ON_BACK_PRESS",
    )
}
