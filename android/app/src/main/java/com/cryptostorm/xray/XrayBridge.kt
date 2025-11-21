package com.cryptostorm.xray
/**
 * Single entry-point for all JNI symbols we expose from libxray_jni.so
 */
object XrayBridge {
    init { System.loadLibrary("xray_jni") }
    external fun StartXray(configPath: String): Int
    external fun stopXray(): Int
}

// ── Result type for updater logging
internal enum class UpdateResult {
     THROTTLED,     // skipped due to interval
     NO_CHANGE,     // hash matched; nothing to do
     UPDATED,       // pulled and applied new list
     NETWORK_ERROR  // couldn’t fetch hash/list
}