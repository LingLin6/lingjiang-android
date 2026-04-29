package com.linglin.lingjiang.network

fun endpoint(baseUrl: String, suffix: String): String {
    val cleanBase = baseUrl.trim().trimEnd('/')
    val cleanSuffix = suffix.trim().trimStart('/')
    if (cleanBase.contains("127.0.0.1") || cleanBase.contains("localhost")) {
        return "$cleanBase/$cleanSuffix"
    }
    return when {
        cleanBase.endsWith(cleanSuffix) -> cleanBase
        cleanBase.endsWith("/v1") -> "$cleanBase/$cleanSuffix"
        else -> "$cleanBase/v1/$cleanSuffix"
    }
}
