package com.sakura.player.download

import android.util.Log
import com.sakura.player.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object CdnProber {
    private const val TAG = "CdnProber"

    data class ProbeResult(
        val workingUrl: String,       // The URL that actually works (may differ from input if redirected)
        val strategy: DownloadStrategy,
        val diagnostics: String       // Human-readable explanation
    )

    enum class DownloadStrategy {
        TLS_1_2,    // HTTPS with forced TLS 1.2
        TLS_1_3,    // HTTPS default (TLS 1.3)
        HTTP_ONLY,  // Plain HTTP (for CDNs with broken HTTPS)
        UNREACHABLE // Nothing works
    }

    suspend fun probe(m3u8Url: String, referer: String): ProbeResult = withContext(Dispatchers.IO) {
        val headers = HttpClient.browserHeaders(referer)

        // Strategy 1: TLS 1.2 (most CDNs accept this)
        try {
            val req = buildRequest(m3u8Url, headers)
            val resp = HttpClient.client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                resp.close()
                if (isValidM3u8(body)) {
                    Log.e(TAG, "TLS 1.2 probe succeeded for $m3u8Url")
                    return@withContext ProbeResult(m3u8Url, DownloadStrategy.TLS_1_2, "TLS 1.2 连接成功，m3u8 内容有效")
                }
                Log.d(TAG, "TLS 1.2: connected but body is not valid m3u8: ${body.take(200)}")
            } else {
                resp.close()
                Log.d(TAG, "TLS 1.2: HTTP ${resp.code}")
            }
        } catch (e: javax.net.ssl.SSLException) {
            Log.d(TAG, "TLS 1.2 SSL failed: ${e.message}")
        } catch (e: java.net.SocketException) {
            Log.d(TAG, "TLS 1.2 socket failed: ${e.message}")
        } catch (e: Exception) {
            Log.d(TAG, "TLS 1.2 failed: ${e.message}")
        }

        // Strategy 2: TLS 1.3
        try {
            val req = buildRequest(m3u8Url, headers)
            val resp = HttpClient.clientTls13.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                resp.close()
                if (isValidM3u8(body)) {
                    Log.e(TAG, "TLS 1.3 probe succeeded for $m3u8Url")
                    return@withContext ProbeResult(m3u8Url, DownloadStrategy.TLS_1_3, "TLS 1.3 连接成功")
                }
                Log.d(TAG, "TLS 1.3: connected but body is not valid m3u8")
            } else {
                resp.close()
                Log.d(TAG, "TLS 1.3: HTTP ${resp.code}")
            }
        } catch (e: javax.net.ssl.SSLException) {
            Log.d(TAG, "TLS 1.3 SSL failed: ${e.message}")
        } catch (e: java.net.SocketException) {
            Log.d(TAG, "TLS 1.3 socket failed: ${e.message}")
        } catch (e: Exception) {
            Log.d(TAG, "TLS 1.3 failed: ${e.message}")
        }

        // Strategy 3: HTTP
        val httpUrl = m3u8Url.replaceFirst("https://", "http://")
        try {
            val req = buildRequest(httpUrl, headers)
            val resp = HttpClient.clientHttp.newCall(req).execute()

            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                resp.close()
                if (isValidM3u8(body)) {
                    Log.e(TAG, "HTTP probe succeeded for $httpUrl")
                    return@withContext ProbeResult(httpUrl, DownloadStrategy.HTTP_ONLY, "HTTP 明文连接成功")
                }
                Log.d(TAG, "HTTP: connected but body is not valid m3u8")
            } else {
                // Check for redirect to bare IP (expired token)
                if (resp.code == 302) {
                    val location = resp.header("Location") ?: ""
                    resp.close()
                    if (location.matches(Regex("http://\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        Log.e(TAG, "HTTP 302 redirect to bare IP: $location — token expired")
                        return@withContext ProbeResult(m3u8Url, DownloadStrategy.UNREACHABLE,
                            "CDN token 已过期（跳转到裸IP），请重新获取播放页")
                    }
                    Log.d(TAG, "HTTP: 302 to $location (not bare IP)")
                } else {
                    resp.close()
                    Log.d(TAG, "HTTP: HTTP ${resp.code}")
                }
            }
        } catch (e: javax.net.ssl.SSLException) {
            Log.d(TAG, "HTTP SSL failed (unexpected): ${e.message}")
        } catch (e: java.net.SocketException) {
            Log.d(TAG, "HTTP socket failed: ${e.message}")
        } catch (e: Exception) {
            Log.d(TAG, "HTTP failed: ${e.message}")
        }

        Log.e(TAG, "All strategies exhausted for $m3u8Url")
        ProbeResult(m3u8Url, DownloadStrategy.UNREACHABLE, "所有连接方式均失败")
    }

    private fun isValidM3u8(content: String): Boolean {
        return content.contains("#EXTM3U") || content.contains("#EXT-X-STREAM-INF")
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }
}
