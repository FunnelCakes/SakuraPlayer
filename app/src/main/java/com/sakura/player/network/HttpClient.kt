package com.sakura.player.network

import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object HttpClient {
    // Shared cookie store — cookies set by the scraper are visible to the downloader
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val domainCookies = mutableListOf<Cookie>()
            cookieStore.values.forEach { list ->
                list.forEach { cookie ->
                    if (cookie.matches(url)) domainCookies.add(cookie)
                }
            }
            return domainCookies
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val key = url.host
            val list = cookieStore.getOrPut(key) { mutableListOf() }
            cookies.forEach { newCookie ->
                list.removeAll { it.name == newCookie.name }
                list.add(newCookie)
            }
        }
    }

    // Shared dispatcher — allows up to 32 concurrent requests (default is 64 total, 5 per host)
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 32
    }

    // Shared connection pool — keep 20 idle connections alive for reuse
    private val sharedPool = ConnectionPool(20, 5, TimeUnit.MINUTES)

    // Force TLS 1.2 only - some CDNs reject TLS 1.3 connections (fingerprinting)
    private val tls12Spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_2)
        .build()

    // TLS 1.3 capable - uses the full MODERN_TLS spec
    private val tls13Spec = ConnectionSpec.MODERN_TLS

    // HTTP cleartext - no TLS at all
    private val cleartextSpec = ConnectionSpec.CLEARTEXT

    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedPool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .connectionSpecs(listOf(tls12Spec))
        .build()

    val clientTls13: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedPool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .connectionSpecs(listOf(tls13Spec))
        .build()

    val clientHttp: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectionPool(sharedPool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .cookieJar(cookieJar)
        .connectionSpecs(listOf(cleartextSpec))
        .build()

    /** Browser-like headers used for anti-leech CDN requests */
    fun browserHeaders(referer: String = "https://yinghua14.com/"): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer" to referer,
        "Origin" to referer.trimEnd('/'),
        "Connection" to "keep-alive"
    )
}
