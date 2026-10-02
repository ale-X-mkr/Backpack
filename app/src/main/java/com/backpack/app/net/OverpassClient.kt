package com.backpack.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.backpack.app.SettingsActivity
import com.backpack.app.core.OverpassCheck
import com.backpack.app.route.RoutePlanner
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One way to ask Overpass (OpenStreetMap data) for places, speed limits and
 * sights:
 * - servers in turn: your own server from Settings (if set), then the
 *   FOSSGIS main server, then Private.coffee (formerly kumi.systems). Both
 *   free, no key. VK Maps (mail.ru) left out on purpose.
 * - a busy answer (429/5xx) or a hidden server timeout ("runtime error"
 *   remark in an HTTP 200 answer) moves on to the next server;
 * - after every server failed, one more round 3 s later;
 * - long read timeout: Overpass sends nothing until the query is done.
 * Callbacks: [onSwitch] and [done] run on the main thread.
 */
object OverpassClient {

    const val MAIN = "https://overpass-api.de/api/interpreter"
    const val PRIVATE_COFFEE = "https://overpass.private.coffee/api/interpreter"
    const val USER_AGENT = "Backpack/0.8 (private Android navigation app; OpenStreetMap data)"

    private val main = Handler(Looper.getMainLooper())

    private val longHttp = RoutePlanner.http.newBuilder().readTimeout(40, TimeUnit.SECONDS).build()
    private val shortHttp = RoutePlanner.http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()

    enum class Failure(val message: String) {
        TIMEOUT("Places servers too slow right now (timeout)"),
        BUSY("Places servers busy right now"),
        OFFLINE("Places search failed -- no connection"),
        BAD_ANSWER("Places servers sent an unreadable answer")
    }

    fun servers(c: Context): List<String> {
        val own = c.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsActivity.KEY_OVERPASS_URL, "")?.trim().orEmpty()
        return (listOf(own) + listOf(MAIN, PRIVATE_COFFEE)).filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
    }

    /**
     * @param quick short timeout and a single round (speed-limit lookups
     *  run every 15 s: a stale answer is useless).
     */
    fun query(
        c: Context,
        query: String,
        quick: Boolean = false,
        onSwitch: (() -> Unit)? = null,
        done: (body: String?, failure: Failure?) -> Unit
    ) {
        val list = servers(c.applicationContext)
        attempt(list, 0, round = 0, maxRounds = if (quick) 1 else 2, query = query, quick = quick, lastFailure = null, onSwitch = onSwitch, done = done)
    }

    private fun attempt(
        list: List<String>, index: Int, round: Int, maxRounds: Int, query: String, quick: Boolean,
        lastFailure: Failure?, onSwitch: (() -> Unit)?, done: (String?, Failure?) -> Unit
    ) {
        if (index >= list.size) {
            if (round + 1 < maxRounds) {
                main.postDelayed({ attempt(list, 0, round + 1, maxRounds, query, quick, lastFailure, onSwitch, done) }, 3000L)
            } else {
                main.post { done(null, lastFailure ?: Failure.OFFLINE) }
            }
            return
        }
        fun next(f: Failure) {
            if (index + 1 < list.size && onSwitch != null) main.post { onSwitch() }
            attempt(list, index + 1, round, maxRounds, query, quick, f, onSwitch, done)
        }
        val req = Request.Builder().url(list[index])
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .post(FormBody.Builder().add("data", query).build())
            .build()
        (if (quick) shortHttp else longHttp).newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                next(if (e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException) Failure.TIMEOUT else Failure.OFFLINE)
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                when {
                    body == null -> next(if (code == 504) Failure.TIMEOUT else Failure.BUSY)
                    // Not JSON (e.g. a wrong own-server URL returning a web page).
                    !body.trimStart().startsWith("{") -> next(Failure.BAD_ANSWER)
                    OverpassCheck.runtimeError(body) != null -> next(Failure.TIMEOUT)
                    else -> main.post { done(body, null) }
                }
            }
        })
    }
}
