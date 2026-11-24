package com.example.durakoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("durak_overlay", Context.MODE_PRIVATE)
    var fontSp: Int
        get() = sp.getInt("font", 13)
        set(v) = sp.edit().putInt("font", v).apply()
    var alphaPct: Int
        get() = sp.getInt("alpha", 75)
        set(v) = sp.edit().putInt("alpha", v).apply()
    var x: Int
        get() = sp.getInt("x", 0)
        set(v) = sp.edit().putInt("x", v).apply()
    var y: Int
        get() = sp.getInt("y", 200)
        set(v) = sp.edit().putInt("y", v).apply()
    var deckSize: Int
        get() = sp.getInt("deck", 36)
        set(v) = sp.edit().putInt("deck", v).apply()
}

class OverlayService : Service() {

    companion object {
        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var panel: LinearLayout
    private lateinit var tv: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private var attached = false

    private val stateListener: (GameState) -> Unit = { render(it) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "x", "y" -> {
                lp.x = prefs.x; lp.y = prefs.y
                if (attached) wm.updateViewLayout(panel, lp)
            }
            else -> {
                applyStyle(); render(GameStore.state)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        buildPanel()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        GameStore.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        GameStore.removeListener(stateListener)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (attached) wm.removeView(panel)
        attached = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("overlay", "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Durak Overlay")
            .setContentText("Аналитика активна")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildPanel() {
        tv = TextView(this).apply { setTextColor(Color.WHITE) }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(tv)
        }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.x
            y = prefs.y
        }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        panel.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(panel, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
                    prefs.x = lp.x; prefs.y = lp.y
                    prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
                    true
                }
                else -> false
            }
        }

        applyStyle()
        wm.addView(panel, lp)
        attached = true
    }

    private fun applyStyle() {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSp.toFloat())
        panel.background = GradientDrawable().apply {
            setColor(Color.argb(prefs.alphaPct * 255 / 100, 0, 0, 0))
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun cardHtml(c: Card) = "<font color='${suitColor(c.suit)}'>$c</font>"
    private fun suitColor(s: Int) = if (s == 1 || s == 2) "#FF6B6B" else "#FFFFFF"
    private fun suitHtml(s: Int) = "<font color='${suitColor(s)}'>${Card.SUITS[s]}</font>"

    private fun render(s: GameState) {
        if (!attached) return
        val sb = StringBuilder()
        sb.append("<b>Козырь:</b> ")
            .append(s.trump?.let { cardHtml(it) } ?: s.trumpSuit?.let { suitHtml(it) } ?: "—")
        sb.append("<br><b>Соперник:</b> ").append(if (s.oppCount >= 0) s.oppCount.toString() else "—")
        sb.append("<br><b>Колода:</b> ").append(if (s.deckLeft >= 0) s.deckLeft.toString() else "—")
        sb.append("<br><b>Стол:</b> ")
            .append(if (s.table.isEmpty()) "—" else s.table.joinToString(" ") { cardHtml(it) })
        sb.append("<br><b>Вышло (${s.seen.size}):</b>")
        if (s.seen.isEmpty()) {
            sb.append(" —")
        } else {
            for (suit in 0..3) {
                val cards = s.seen.filter { it.suit == suit }.sortedBy { it.rank }
                if (cards.isEmpty()) continue
                sb.append("<br>").append(suitHtml(suit)).append(" ")
                    .append(cards.joinToString(" ") { Card.rankName(it.rank) })
            }
        }
        sb.append("<br><small>${s.status} · #${s.msgCount}</small>")
        tv.text = Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_LEGACY)
    }
}
