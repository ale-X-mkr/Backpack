package com.backpack.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import com.backpack.app.Prefs
import com.backpack.app.Settings
import com.backpack.app.core.OrsKey
import com.backpack.app.route.RoutePlanner
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 1.5.0: "your own free routing key" in a few taps. The account itself is
 * made on openrouteservice's site (email + confirmation, their rule); the
 * rest is done here: the sign-up page opens, the key copied there is found
 * on the clipboard when you come back, checked with one tiny route, saved.
 */
object KeyGuide {

    private var popup: Popup? = null
    private var field: EditText? = null
    private var status: TextView? = null
    private var onSaved: (() -> Unit)? = null
    private var checking = false
    private val main = Handler(Looper.getMainLooper())

    val isOpen: Boolean get() = popup?.dialog?.isShowing == true

    fun show(c: Context, saved: () -> Unit) {
        if (isOpen) return
        onSaved = saved
        val p = Popup(c, "Your free routing key")
        p.text("Routes come from openrouteservice, a free non-profit service (HeiGIT, Heidelberg). " +
            "Each person uses their own free key: your routes stay on your own account, with your own daily quota. " +
            "Map, places, sights and the speed alarm work without it.", 13f)
        p.section("2–3 minutes")
        p.text("1. Get my free key: create the account (email + confirmation).\n" +
            "2. Create a key (token) there and press its copy button.\n" +
            "3. Come back to Backpack: the key is found and checked by itself.", 13f)
        p.bodyButton("Get my free key") {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.ORS_SIGNUP_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                setStatus("No browser found: open ${Prefs.ORS_SIGNUP_URL}")
            }
        }
        field = p.field("Or paste your key here", "", "key",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) { }
        status = p.text("", 13f)
        p.button("Later") { p.dismiss() }
        p.button("Check and save", primary = true) {
            val k = field?.text?.toString()?.trim().orEmpty()
            if (!OrsKey.plausible(k)) setStatus("That doesn't look like a key (no spaces, quite long).")
            else check(c, k)
        }
        p.dialog.setOnDismissListener { popup = null; field = null; status = null; checking = false }
        popup = p
        p.show()
        // The guide's own window gets the focus back from the browser (not the map screen).
        p.dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has -> if (has) lookAtClipboard(c) }
        // Copied before opening the guide (e.g. second try).
        main.postDelayed({ lookAtClipboard(c) }, 400L)
    }

    /** The screen got the focus back (e.g. from the browser): a key copied there? */
    fun lookAtClipboard(c: Context) {
        if (!isOpen || checking) return
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = try { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c) } catch (e: Exception) { null }
        val key = OrsKey.fromClipboard(text) ?: return
        if (key == Prefs.settings(c).getString(Settings.KEY_ORS_KEY, "")?.trim()) return
        field?.setText(key)
        setStatus("Key found on the clipboard, checking…")
        check(c, key)
    }

    private fun setStatus(s: String) { status?.text = s; popup?.fit() }

    /** One tiny route in Heidelberg with [key]; saved when it works (or only today's quota is used up). */
    private fun check(c: Context, key: String) {
        if (checking) return
        checking = true
        setStatus("Checking the key…")
        val app = c.applicationContext
        val url = "${Prefs.DEFAULT_ORS_URL}/v2/directions/driving-car?start=8.681495,49.41461&end=8.687872,49.420318"
        val req = Request.Builder().url(url).addHeader("Authorization", key)
            .addHeader("Accept", "application/json, application/geo+json").build()
        RoutePlanner.http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { done(null) }
            override fun onResponse(call: Call, response: Response) { response.close(); done(response.code) }
            private fun done(code: Int?) { main.post {
                checking = false
                when (OrsKey.checkFromHttp(code)) {
                    OrsKey.Check.OK, OrsKey.Check.QUOTA -> {
                        save(app, key)
                        setStatus("✓ Key works, saved. You can plan routes now.")
                        main.postDelayed({ popup?.dismiss() }, 1500L)
                        onSaved?.invoke()
                    }
                    OrsKey.Check.REFUSED -> setStatus("openrouteservice refused this key. Copy it again from your account (the whole key).")
                    OrsKey.Check.UNREACHABLE -> {
                        save(app, key)
                        setStatus("Saved, but it couldn't be checked now (no connection?). It will be used for the next route.")
                        onSaved?.invoke()
                    }
                }
            } }
        })
    }

    private fun save(c: Context, key: String) {
        Prefs.settings(c).edit().putString(Settings.KEY_ORS_KEY, key).apply()
    }
}
