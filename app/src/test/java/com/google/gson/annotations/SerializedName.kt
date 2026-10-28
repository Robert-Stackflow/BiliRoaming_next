package com.google.gson.annotations

/** Host annotation fixture: the module does not bundle the client's Gson. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class SerializedName(val value: String)
