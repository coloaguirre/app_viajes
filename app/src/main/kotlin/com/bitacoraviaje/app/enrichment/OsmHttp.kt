package com.bitacoraviaje.app.enrichment

import android.content.Context
import com.bitacoraviaje.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * One HTTP client for the OSM and Wikimedia services (skill place-enrichment): identifiable User-Agent,
 * 15 s timeouts, a 10 MB cache, one request per second per host (Nominatim's policy)
 * and backoff on 429/5xx. Everything that talks to the internet goes through here, so
 * the resilience policy of PLAN 3.6 lives in one place.
 */
@Singleton
class OsmHttp @Inject constructor(@ApplicationContext context: Context) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "osm-http"), 10L * 1024 * 1024))
        .addInterceptor(Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).header("Accept-Language", "es").build())
        })
        .addInterceptor(Backoff())
        .addNetworkInterceptor(RateLimit()) // after the cache: a cache hit does not wait (review M11)
        .build()

    /** Token bucket per host: at most one request per [minIntervalMs] (1/s Nominatim, 1 per 2 s Overpass). */
    class RateLimit : Interceptor {
        private val last = HashMap<String, Long>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val host = chain.request().url.host
            val interval = when {
                chain.request().url.encodedPath.endsWith("/interpreter") -> 2_000L // Overpass endpoint, whatever the host
                host.endsWith("wikipedia.org") || host.endsWith("wikidata.org") -> 200L // Wikimedia: 5/s is fine for a client
                else -> 1_000L // Nominatim policy
            }
            val wait = synchronized(last) {
                val now = System.currentTimeMillis()
                val due = (last[host] ?: 0L) + interval
                val w = (due - now).coerceAtLeast(0L)
                last[host] = maxOf(now, due) + 0L
                w
            }
            if (wait > 0) Thread.sleep(wait)
            return chain.proceed(chain.request())
        }
    }

    /** 429 / 503 / 504 (busy public instances): retry up to 3 times with 2 s, 4 s, 8 s (+ Retry-After when given). */
    class Backoff : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var attempt = 0
            while (true) {
                val response = try {
                    chain.proceed(chain.request())
                } catch (e: IOException) {
                    if (attempt >= MAX_RETRIES) throw e
                    attempt++
                    Thread.sleep(2_000L shl (attempt - 1))
                    continue
                }
                if (response.code !in RETRY_CODES || attempt >= MAX_RETRIES) return response
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1_000)?.coerceAtMost(MAX_RETRY_AFTER_MS)
                response.close()
                attempt++
                Thread.sleep(retryAfter ?: (2_000L shl (attempt - 1)))
            }
        }

        companion object {
            const val MAX_RETRIES = 3
            const val MAX_RETRY_AFTER_MS = 30_000L
            val RETRY_CODES = setOf(429, 503, 504)
        }
    }

    companion object {
        /** Required by Nominatim's usage policy: identifies the app, not the user. */
        const val USER_AGENT = "BitacoraViaje/${BuildConfig.VERSION_NAME} (https://github.com/coloaguirre/app_viajes)"

        fun get(url: String): Request = Request.Builder().url(url).get().build()
    }
}
