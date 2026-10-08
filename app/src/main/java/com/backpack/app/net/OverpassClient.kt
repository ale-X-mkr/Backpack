package com.backpack.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.backpack.app.Settings
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
 * - long read timeout: Overpass sends nothing until the query is done;
 * - 1.0.0: before a query, the server's status page says whether a query
 *   slot is free. A server with no free slot for more than a few seconds is
 *   skipped in the first round instead of making us wait for a refusal.
 *   The answer is remembered for 30 s (cheap: a tiny text page).
 * - 1.2.1: a server with your own key (Settings > Servers) goes first; if
 *   the first server has not answered within [HEDGE_AFTER_MS], the next one
 *   is asked too and the first answer wins; background fills (services not
 *   ticked yet) run only when nothing else waits, one at a time.
 * Callbacks: [onSwitch] and [done] run on the main thread.
 */
object OverpassClient {

    const val MAIN = "https://overpass-api.de/api/interpreter"
    const val PRIVATE_COFFEE = "https://overpass.private.coffee/api/interpreter"
    const val USER_AGENT = "Backpack/1.1 (private Android navigation app, non-commercial; OpenStreetMap / Wikimedia data)"

    private val main = Handler(Looper.getMainLooper())

    private val longHttp = RoutePlanner.http.newBuilder().readTimeout(40, TimeUnit.SECONDS).build()
    private val shortHttp = RoutePlanner.http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    private val statusHttp = RoutePlanner.http.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()

    /** Longest wait for a free slot we accept (seconds); longer = next server. */
    private const val MAX_SLOT_WAIT_S = 4

    /** server -> (checked at, seconds to wait then). */
    private val statusMemo = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int?>>()

    enum class Failure(val message: String) {
        TIMEOUT("Places servers too slow right now (timeout)"),
        BUSY("Places servers busy right now"),
        OFFLINE("Places search failed -- no connection"),
        BAD_ANSWER("Places servers sent an unreadable answer")
    }

    fun servers(c: Context): List<String> {
        val prefs = c.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val own = prefs.getString(Settings.KEY_OVERPASS_URL, "")?.trim().orEmpty()
        val keyed = com.backpack.app.core.OverpassProviders.byId(prefs.getString(Settings.KEY_OVERPASS_PROVIDER, null))
            ?.url(prefs.getString(Settings.KEY_OVERPASS_KEY, "").orEmpty()).orEmpty()
        return (listOf(keyed, own) + listOf(MAIN, PRIVATE_COFFEE)).filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
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
        /** Heavy query (1.0.7 sights within reach): each server once, no second round. */
        singleRound: Boolean = false,
        /** Speed limits of the road ahead: first in the queue. */
        priority: Boolean = false,
        /** Replaced search: skipped when its turn comes (answered with nothing). */
        isStale: (() -> Boolean)? = null,
        /** 1.2.1: filling the cache ahead of need: only when nothing else waits. */
        background: Boolean = false,
        done: (body: String?, failure: Failure?) -> Unit
    ) {
        val list = servers(c.applicationContext)
        val rounds = if (quick || singleRound) 1 else 2
        // Speed limits (quick) run on their own; every other query waits its
        // turn, so places, sights and emergency layers never hold more than
        // the 2 slots a user gets on the public servers (1.0.9).
        if (quick) { attempt(list, 0, 0, rounds, query, true, null, onSwitch, null, done); return }
        val job = HeavyJob(isStale, { done(null, null) }) { finished ->
            val race = Race()
            val once: (String?, Failure?) -> Unit = { body, failure ->
                if (!race.finished) { race.finished = true; done(body, failure); finished() }
            }
            attempt(list, 0, 0, rounds, query, false, null, onSwitch, race, once)
            // 1.2.1: the first server is slow: ask the next one too, first answer wins.
            if (!background && list.size > 1) main.postDelayed({
                if (!race.finished && !race.advanced && isStale?.invoke() != true) {
                    send(list[1], query, false, { body, _ -> if (body != null) once(body, null) }) { _ -> /* the main try goes on */ }
                }
            }, HEDGE_AFTER_MS)
        }
        main.post {
            when {
                background -> backgroundQueue.addLast(job)
                priority -> heavyQueue.addFirst(job)
                else -> heavyQueue.addLast(job)
            }
            runNextHeavy()
        }
    }

    /** One query's state: answered already; moved past the first server. */
    private class Race { var finished = false; var advanced = false }
    private const val HEDGE_AFTER_MS = 4000L
    /** 1.2.1: background fills, after every waiting query. */
    private val backgroundQueue = ArrayDeque<HeavyJob>()

    private class HeavyJob(val isStale: (() -> Boolean)?, val skip: () -> Unit, val run: (finished: () -> Unit) -> Unit)

    private val heavyQueue = ArrayDeque<HeavyJob>()
    /** Heavy queries running now (ids); 1.1.1: two at a time, the public limit. */
    private val heavyRunning = HashSet<Int>()
    private var heavyRun = 0
    private const val HEAVY_SLOTS = 2

    /** Main thread only. A watchdog frees a slot if its query never reports back (3 min). */
    private fun runNextHeavy() {
        while (heavyRunning.size < HEAVY_SLOTS) {
            // Background fills only on an idle client, one at a time (a slot stays free).
            val job = heavyQueue.removeFirstOrNull()
                ?: (if (heavyRunning.isEmpty()) backgroundQueue.removeFirstOrNull() else null)
                ?: return
            if (job.isStale?.invoke() == true) { job.skip(); continue }
            val run = ++heavyRun
            heavyRunning.add(run)
            val release = { if (heavyRunning.remove(run)) runNextHeavy() }
            main.postDelayed({ release() }, 180_000L)
            job.run { main.post { release() } }
        }
    }

    private fun attempt(
        list: List<String>, index: Int, round: Int, maxRounds: Int, query: String, quick: Boolean,
        lastFailure: Failure?, onSwitch: (() -> Unit)?, race: Race?, done: (String?, Failure?) -> Unit
    ) {
        if (race?.finished == true) return // the other server answered first
        if (index >= list.size) {
            if (round + 1 < maxRounds) {
                main.postDelayed({ attempt(list, 0, round + 1, maxRounds, query, quick, lastFailure, onSwitch, race, done) }, 3000L)
            } else {
                main.post { done(null, lastFailure ?: Failure.OFFLINE) }
            }
            return
        }
        fun next(f: Failure) {
            race?.advanced = true
            if (race?.finished == true) return
            if (index + 1 < list.size && onSwitch != null) main.post { onSwitch() }
            attempt(list, index + 1, round, maxRounds, query, quick, f, onSwitch, race, done)
        }
        // First round: ask the status page first. Last round: just try.
        if (round + 1 < maxRounds || quick) {
            withSlot(list[index], { next(Failure.BUSY) }) { send(list[index], query, quick, done) { f -> next(f) } }
        } else {
            send(list[index], query, quick, done) { f -> next(f) }
        }
    }

    /** Runs [go] when [server] has (or soon has) a free slot, else [busy]. */
    private fun withSlot(server: String, busy: () -> Unit, go: () -> Unit) {
        val statusUrl = OverpassCheck.statusUrl(server) ?: return go()
        val now = System.currentTimeMillis()
        statusMemo[server]?.let { (at, wait) ->
            if (now - at < 30_000L) {
                val left = wait?.let { it - ((now - at) / 1000).toInt() }
                when {
                    left == null || left <= 0 -> go()
                    left <= MAX_SLOT_WAIT_S -> main.postDelayed({ go() }, left * 1000L)
                    else -> busy()
                }
                return
            }
        }
        val req = Request.Builder().url(statusUrl).addHeader("User-Agent", USER_AGENT).build()
        statusHttp.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = go() // no status page: just try
            override fun onResponse(call: Call, response: Response) {
                // A broken read must still go on (else the query never runs).
                val text = try { response.use { if (it.isSuccessful) it.body?.string() else null } } catch (e: Exception) { null }
                val wait = text?.let { OverpassCheck.statusWait(it) }
                statusMemo[server] = System.currentTimeMillis() to wait
                when {
                    wait == null || wait <= 0 -> go()
                    wait <= MAX_SLOT_WAIT_S -> main.postDelayed({ go() }, wait * 1000L)
                    else -> busy()
                }
            }
        })
    }

    private fun send(server: String, query: String, quick: Boolean, done: (String?, Failure?) -> Unit, next: (Failure) -> Unit) {
        val req = Request.Builder().url(server)
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
                // A timeout / reset while reading counts as a failed server, never a lost answer.
                val body = try { response.use { if (it.isSuccessful) it.body?.string() else null } }
                    catch (e: Exception) { next(Failure.TIMEOUT); return }
                when {
                    body == null -> {
                        if (code == 429) statusMemo[server] = System.currentTimeMillis() to 60
                        next(if (code == 504) Failure.TIMEOUT else Failure.BUSY)
                    }
                    // Not JSON (e.g. a wrong own-server URL returning a web page).
                    !body.trimStart().startsWith("{") -> next(Failure.BAD_ANSWER)
                    OverpassCheck.runtimeError(body) != null -> next(Failure.TIMEOUT)
                    else -> main.post { done(body, null) }
                }
            }
        })
    }
}
