package com.sakura.player.scraper

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object DomainFinder {
    private const val TAG = "DomainFinder"

    private val DOMAINS = listOf(
        "https://yinghua14.com",
        "https://www.yhdmw2.com",
        "https://www.156yh.cc",
        "https://hazardinfo.com",
        "https://sqynyl.com",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun findDomain(): String = withContext(Dispatchers.IO) {
        for (domain in DOMAINS) {
            try {
                val req = Request.Builder().url(domain).head().build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    Log.i(TAG, "Found working domain: $domain")
                    return@withContext domain
                }
            } catch (_: Exception) {
                Log.w(TAG, "Domain unreachable: $domain")
            }
        }
        Log.e(TAG, "All domains unreachable")
        "" // 返回空字符串，由调用方处理
    }

    fun getDomainList(): List<String> = DOMAINS

    suspend fun findNextDomain(current: String): String = withContext(Dispatchers.IO) {
        val idx = DOMAINS.indexOf(current)
        val candidates = if (idx >= 0) DOMAINS.drop(idx + 1) + DOMAINS.take(idx) else DOMAINS
        for (domain in candidates) {
            try {
                val req = Request.Builder().url(domain).head().build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) return@withContext domain
            } catch (_: Exception) {}
        }
        ""
    }
}
