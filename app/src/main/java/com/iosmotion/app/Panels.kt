package com.iosmotion.app

import android.app.Notification
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ================================================================ Control Center
class ControlCenterView(ctx: Context, private val host: PanelHost, private val bd: View?) : LinearLayout(ctx) {
    private val ink get() = Palette.text(context)
    private val handler = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val cr = ctx.contentResolver
    private val bright = GlassSlider(ctx, GlassSlider.Mode.PILL)
    private val vol = GlassSlider(ctx, GlassSlider.Mode.PILL)
    private val mTitle = Ui.tv(ctx, "", 14f, Palette.text(ctx), Typeface.BOLD).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val mSub = Ui.tv(ctx, "", 12f, Palette.text2(ctx)).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val mPlay = ImageView(ctx)
    private val bPct = Ui.tv(ctx, "--%", 26f, Palette.text(ctx), Typeface.BOLD)
    private val bInfo = Ui.tv(ctx, "", 12.5f, Palette.text2(ctx))
    private val bFill = View(ctx).apply { background = Ui.round(Color.WHITE, ctx.dp(3f)) }
    private val bRest = View(ctx)
    private var torchCb: CameraManager.TorchCallback? = null
    private var ctrl: MediaController? = null
    private var playing = false
    private lateinit var tAir: Tile
    private lateinit var tWifi: Tile
    private lateinit var tBt: Tile
    private lateinit var tRot: Tile
    private lateinit var tTorch: Tile

    private val tick = object : Runnable {
        override fun run() { refreshStates(); handler.postDelayed(this, 1000L) }
    }

    inner class Tile(iconRes: Int, private val accent: Int, onClick: () -> Unit) {
        val view = GlassView(context)
        private val fill = View(context)
        private val img = ImageView(context)
        private var on: Boolean? = null

        init {
            view.corner = context.dp(28f)
            view.elev = 0f
            fill.background = Ui.oval(accent)
            fill.alpha = 0f
            view.addView(fill, flp(MATCH, MATCH))
            img.setImageResource(iconRes)
            view.addView(img, flp(context.dpi(26f), context.dpi(26f), Gravity.CENTER))
            view.setOnClickListener { Haptics.tick(it); onClick() }
            Motion.elastic(view)
            setOn(false)
        }

        fun setOn(v: Boolean) {
            if (on == v) return
            on = v
            fill.animate().alpha(if (v) 1f else 0f).setDuration(Fx.dur(200L)).start()
            img.setColorFilter(if (v) Color.WHITE else ink)
        }
    }

    private fun open(action: String) = host.launch(Intent(action))

    private fun canWrite(): Boolean {
        if (Settings.System.canWrite(context)) return true
        host.toast(L.t("Allow \"Modify system settings\" for iOS MOTION 26 first", "Önce iOS MOTION 26 için \"Sistem ayarlarını değiştir\" iznini verin"))
        host.launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
        return false
    }

    private fun grid(vararg t: Tile): LinearLayout {
        val g = LinearLayout(context).apply { orientation = VERTICAL }
        var i = 0
        while (i < t.size) {
            val r = LinearLayout(context).apply { orientation = HORIZONTAL }
            val m = context.dpi(5f)
            val s = context.dpi(56f)
            r.addView(t[i].view, llp(s, s, l = m, t = m, r = m, b = m))
            if (i + 1 < t.size) r.addView(t[i + 1].view, llp(s, s, l = m, t = m, r = m, b = m))
            g.addView(r)
            i += 2
        }
        return g
    }

    init {
        orientation = VERTICAL
        val c = context
        val gap = c.dpi(12f)

        tAir = Tile(R.drawable.ic_airplane, Palette.orange) { open(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
        tWifi = Tile(R.drawable.ic_wifi, Palette.accent) { host.launch(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)) }
        tBt = Tile(R.drawable.ic_bluetooth, Palette.accent) { open(Settings.ACTION_BLUETOOTH_SETTINGS) }
        tRot = Tile(R.drawable.ic_rotate, Palette.red) {
            if (canWrite()) {
                val cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (cur == 1) 0 else 1)
                refreshStates()
            }
        }
        tTorch = Tile(R.drawable.ic_flashlight, 0xFFFFD60A.toInt()) {
            if (!Torch.toggle(c)) host.toast(L.t("Flashlight unavailable", "Fener kullanılamıyor"))
        }
        val tCam = Tile(R.drawable.ic_camera, Palette.accent) {
            host.launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        }
        val tLock = Tile(R.drawable.ic_lock, Palette.accent) {
            val svc = MotionAccessibilityService.instance
            if (svc != null && svc.lockScreen()) { host.dismissPanel() } else {
                host.toast(L.t("Enable the iOS MOTION accessibility service to lock the screen", "Ekranı kilitlemek için iOS MOTION erişilebilirlik hizmetini açın"))
                open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        }
        val tSet = Tile(R.drawable.ic_sliders, Palette.accent) { open(Settings.ACTION_SETTINGS) }

        // Row 1: connectivity + music
        val row1 = LinearLayout(c).apply { orientation = HORIZONTAL }
        val conn = Blocks.card(c, bd, 30f, 6f).apply { addView(grid(tAir, tWifi, tBt, tRot)) }
        row1.addView(conn, llp(WRAP, WRAP))
        val music = Blocks.card(c, bd, 30f, 14f)
        val mcol = LinearLayout(c).apply { orientation = VERTICAL }
        mcol.addView(mTitle)
        mcol.addView(mSub, llp(MATCH, WRAP, t = c.dpi(2f)))
        val ctl = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        fun ctlBtn(res: Int, v: ImageView, act: () -> Unit) {
            v.setImageResource(res); v.setColorFilter(ink); v.setPadding(c.dpi(8f), c.dpi(8f), c.dpi(8f), c.dpi(8f))
            v.setOnClickListener { Haptics.tick(it); act() }
            Motion.elastic(v)
            ctl.addView(v, llp(c.dpi(48f), c.dpi(48f)))
        }
        ctlBtn(R.drawable.ic_prev, ImageView(c)) { transport(false, 88) }
        ctlBtn(R.drawable.ic_play, mPlay) { transport(true, 85) }
        ctlBtn(R.drawable.ic_next, ImageView(c)) { transport(false, 87) }
        mcol.addView(View(c), llp(MATCH, 0, 1f))
        mcol.addView(ctl, llp(MATCH, WRAP))
        music.addView(mcol, flp(MATCH, MATCH))
        row1.addView(music, llp(0, MATCH, 1f, l = gap))
        addView(row1, llp(MATCH, c.dpi(150f)))

        // Row 2: sliders + tiles
        val row2 = LinearLayout(c).apply { orientation = HORIZONTAL }
        bright.iconRes = R.drawable.ic_sun
        bright.canStart = { canWrite() }
        bright.onChange = { v, user ->
            if (user) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v * 255).roundToInt().coerceIn(1, 255))
            }
        }
        vol.iconRes = R.drawable.ic_volume
        vol.onChange = { v, user ->
            if (user) am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(), 0)
        }
        val bc = Blocks.card(c, bd, 28f, 0f).apply { addView(bright, flp(MATCH, MATCH)) }
        val vc = Blocks.card(c, bd, 28f, 0f).apply { addView(vol, flp(MATCH, MATCH)) }
        val tc = Blocks.card(c, bd, 30f, 6f).apply { addView(grid(tTorch, tCam, tLock, tSet)) }
        row2.addView(bc, llp(c.dpi(72f), MATCH))
        row2.addView(vc, llp(c.dpi(72f), MATCH, l = gap))
        row2.addView(tc, llp(0, MATCH, 1f, l = gap))
        addView(row2, llp(MATCH, c.dpi(150f), t = gap))

        // Row 3: battery
        val bat = Blocks.card(c, bd, 26f, 14f)
        val bcol = LinearLayout(c).apply { orientation = VERTICAL }
        val top = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.icon(c, R.drawable.ic_battery, 22f, ink), llp(c.dpi(22f), c.dpi(22f), r = c.dpi(10f)))
        top.addView(bPct)
        top.addView(bInfo, llp(WRAP, WRAP, l = c.dpi(12f)))
        bcol.addView(top)
        val bar = LinearLayout(c).apply { orientation = HORIZONTAL; background = Ui.round(0x33FFFFFF, c.dp(3f)) }
        bar.addView(bFill, LinearLayout.LayoutParams(0, MATCH, 50f))
        bar.addView(bRest, LinearLayout.LayoutParams(0, MATCH, 50f))
        bcol.addView(bar, llp(MATCH, c.dpi(6f), t = c.dpi(10f)))
        bat.addView(bcol)
        addView(bat, llp(MATCH, WRAP, t = gap))
    }

    private fun transport(isPlayPause: Boolean, code: Int) {
        val ctl = ctrl
        if (ctl != null) {
            when (code) {
                88 -> ctl.transportControls.skipToPrevious()
                87 -> ctl.transportControls.skipToNext()
                else -> if (playing) ctl.transportControls.pause() else ctl.transportControls.play()
            }
        } else {
            val k = if (isPlayPause) 85 else code
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, k))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, k))
        }
        handler.postDelayed({ refreshStates() }, 400L)
    }

    private fun refreshStates() {
        val c = context
        try { tWifi.setOn((c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled) } catch (_: Throwable) {}
        try { tBt.setOn(BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) } catch (_: Throwable) {}
        tAir.setOn(Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        tRot.setOn(Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) == 0)
        tTorch.setOn(Torch.isOn)
        if (!bright.isDragging) bright.value = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        if (!vol.isDragging) vol.value = am.getStreamVolume(AudioManager.STREAM_MUSIC) / max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toFloat()
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val sc = max(1, bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            val pct = lvl * 100 / sc
            val st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val chg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            val temp = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            bPct.text = "$pct%"
            bInfo.text = (if (chg) L.t("Charging", "Şarj oluyor") else L.t("On battery", "Pilde")) + "  |  " + String.format("%.1f", temp) + " °C"
            (bFill.layoutParams as LinearLayout.LayoutParams).weight = pct.toFloat()
            (bRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).toFloat()
            bFill.requestLayout()
        }
        updateMusic()
    }

    private fun updateMusic() {
        try {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            val list = msm.getActiveSessions(ComponentName(context, NotificationListener::class.java))
            val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
            ctrl = c
            if (c != null) {
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                mTitle.text = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: L.t("Media", "Medya")
                mSub.text = c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: c.packageName
            } else {
                playing = false
                mTitle.text = L.t("Not playing", "Çalan yok")
                mSub.text = ""
            }
        } catch (_: SecurityException) {
            ctrl = null
            mTitle.text = L.t("Media", "Medya")
            mSub.text = L.t("Grant notification access to see track info; buttons still control playback", "Parça bilgisi için bildirim erişimi verin; tuşlar yine de çalar")
        } catch (_: Throwable) {
        }
        mPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        torchCb = Torch.register(context) { tTorch.setOn(it) }
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
        Torch.unregister(context, torchCb)
    }
}

// ================================================================ Notifications
data class Notif(val key: String, val pkg: String, val label: String, val title: String, val text: String, val whenMs: Long, val intent: PendingIntent?)

object NotificationStore {
    val items = ArrayList<Notif>()
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun add(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }

    fun hasAccess(c: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun postRefresh() { main.post { refresh() } }

    fun refresh() {
        items.clear()
        val svc = NotificationListener.instance
        if (svc != null) {
            try {
                val pm = svc.packageManager
                for (s in (svc.activeNotifications ?: emptyArray()).sortedByDescending { it.postTime }) {
                    val n = s.notification
                    if (s.packageName == svc.packageName || s.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
                    val ex = n.extras
                    val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                    val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
                    val label = try { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() } catch (_: Exception) { s.packageName.substringAfterLast('.') }
                    items.add(Notif(s.key, s.packageName, label, title, text, s.postTime, n.contentIntent))
                }
            } catch (_: Throwable) {
            }
        }
        for (l in listeners.toList()) l()
    }

    fun dismiss(key: String) { NotificationListener.instance?.cancelNotification(key); items.removeAll { it.key == key } }
    fun clearAll() { NotificationListener.instance?.cancelAllNotifications(); items.clear(); for (l in listeners.toList()) l() }
}

class NotificationListener : NotificationListenerService() {
    companion object { @Volatile var instance: NotificationListener? = null }
    override fun onListenerConnected() { instance = this; NotificationStore.postRefresh() }
    override fun onListenerDisconnected() { if (instance === this) instance = null; NotificationStore.postRefresh() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { NotificationStore.postRefresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { NotificationStore.postRefresh() }
}

class NotificationListView(ctx: Context, private val host: PanelHost, private val bd: View?) : LinearLayout(ctx) {
    private val seen = HashSet<String>()
    private val expanded = HashSet<String>()
    private val cb: () -> Unit = { render() }

    init { orientation = VERTICAL }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); NotificationStore.add(cb); NotificationStore.refresh() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); NotificationStore.remove(cb) }

    private fun render() {
        removeAllViews()
        val c = context
        val head = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.tv(c, L.t("Notifications", "Bildirimler"), 24f, Palette.text(c), Typeface.BOLD), llp(0, WRAP, 1f))
        if (NotificationStore.hasAccess(c) && NotificationStore.items.isNotEmpty()) {
            val clr = Blocks.button(c, bd, L.t("Clear all", "Tümünü temizle"), 0, R.drawable.ic_clear) { NotificationStore.clearAll() }
            head.addView(clr, llp(WRAP, c.dpi(40f)))
        }
        addView(head, llp(MATCH, WRAP, b = c.dpi(12f)))

        if (!NotificationStore.hasAccess(c)) {
            val card = Blocks.card(c, bd, 24f, 16f)
            val col = LinearLayout(c).apply { orientation = VERTICAL }
            col.addView(Ui.tv(c, L.t("Notification access is off. iOS MOTION 26 never reads notifications without your explicit permission.",
                "Bildirim erişimi kapalı. iOS MOTION 26 açık izniniz olmadan bildirimleri asla okumaz."), 14f, Palette.text(c)))
            col.addView(Blocks.button(c, null, L.t("Grant notification access", "Bildirim erişimi ver"), Palette.accent) {
                host.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, llp(MATCH, c.dpi(48f), t = c.dpi(12f)))
            card.addView(col)
            addView(card, llp(MATCH, WRAP))
            return
        }
        val items = NotificationStore.items
        if (items.isEmpty()) {
            addView(Ui.tv(c, L.t("No notifications", "Bildirim yok"), 15f, Palette.text2(c)), llp(WRAP, WRAP, t = c.dpi(8f)))
            return
        }
        var idx = 0L
        for ((pkg, list) in items.groupBy { it.pkg }) {
            val gh = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val ic = ImageView(c)
            try { ic.setImageDrawable(c.packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { ic.setImageResource(R.drawable.ic_bell); ic.setColorFilter(Palette.text(c)) }
            gh.addView(ic, llp(c.dpi(20f), c.dpi(20f), r = c.dpi(8f)))
            gh.addView(Ui.tv(c, list[0].label + if (list.size > 1) "  (" + list.size + ")" else "", 13f, Palette.text2(c), Typeface.NORMAL, "sans-serif-medium"), llp(0, WRAP, 1f))
            val open = pkg in expanded
            val cards = LinearLayout(c).apply { orientation = VERTICAL }
            val extras = LinearLayout(c).apply { orientation = VERTICAL; visibility = if (open) VISIBLE else GONE }
            val more = Ui.tv(c, if (open) L.t("Show less", "Daha az") else "+" + (list.size - 1) + " " + L.t("more", "daha"), 13f, Palette.accent, Typeface.NORMAL, "sans-serif-medium")
            list.forEachIndexed { i, n ->
                val card = makeCard(n)
                if (i == 0) cards.addView(card, llp(MATCH, WRAP, t = c.dpi(6f))) else extras.addView(card, llp(MATCH, WRAP, t = c.dpi(6f)))
                if (seen.add(n.key)) Motion.enter(card, idx * 45L)
                idx++
            }
            cards.addView(extras)
            if (list.size > 1) {
                more.setPadding(0, c.dpi(8f), 0, c.dpi(4f))
                more.setOnClickListener {
                    Haptics.tick(it)
                    val show = extras.visibility != VISIBLE
                    extras.visibility = if (show) VISIBLE else GONE
                    if (show) { expanded.add(pkg); Motion.enter(extras, 0L, 14f) } else expanded.remove(pkg)
                    more.text = if (show) L.t("Show less", "Daha az") else "+" + (list.size - 1) + " " + L.t("more", "daha")
                }
                cards.addView(more)
            }
            addView(gh, llp(MATCH, WRAP, t = c.dpi(14f)))
            addView(cards, llp(MATCH, WRAP))
        }
    }

    private fun makeCard(n: Notif): GlassView {
        val c = context
        val card = Blocks.card(c, bd, 22f, 14f)
        val col = LinearLayout(c).apply { orientation = VERTICAL }
        if (n.title.isNotEmpty()) col.addView(Ui.tv(c, n.title, 14.5f, Palette.text(c), Typeface.BOLD).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (n.text.isNotEmpty()) col.addView(Ui.tv(c, n.text, 13.5f, Palette.text2(c)).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }, llp(MATCH, WRAP, t = c.dpi(2f)))
        col.addView(Ui.tv(c, DateUtils.getRelativeTimeSpanString(n.whenMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE), 11.5f, Palette.text2(c)), llp(WRAP, WRAP, t = c.dpi(4f)))
        card.addView(col)
        card.isClickable = true
        card.setOnClickListener {
            Haptics.tick(it)
            try { n.intent?.send() } catch (_: Exception) { host.toast(L.t("Cannot open", "Açılamadı")) }
            host.dismissPanel()
        }
        attachSwipe(card, n)
        return card
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachSwipe(v: View, n: Notif) {
        var sx = 0f
        var sy = 0f
        var drag = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; drag = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    if (!drag && abs(dx) > slop * 2 && abs(dx) > abs(e.rawY - sy) * 1.5f) { drag = true; view.parent?.requestDisallowInterceptTouchEvent(true); view.isPressed = false }
                    if (drag) { view.translationX = dx; view.alpha = 1f - (abs(dx) / view.width).coerceIn(0f, 1f) * 0.9f }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag) {
                    val dx = e.rawX - sx
                    if (abs(dx) > view.width * 0.35f) {
                        Haptics.confirm(view)
                        Motion.to(view, DynamicAnimation.TRANSLATION_X, if (dx > 0) view.width.toFloat() else -view.width.toFloat()) {
                            NotificationStore.dismiss(n.key)
                            render()
                        }
                        Motion.to(view, DynamicAnimation.ALPHA, 0f)
                    } else {
                        Motion.to(view, DynamicAnimation.TRANSLATION_X, 0f)
                        Motion.to(view, DynamicAnimation.ALPHA, 1f)
                    }
                }
            }
            drag
        }
    }
}

// ================================================================ Panel layer (overlay sheet with pull / spring settle)
class PanelLayer(
    ctx: Context, private val content: View, private val topInset: Int,
    private val closeAnywhere: Boolean, private val onClosed: () -> Unit
) : FrameLayout(ctx) {
    var onProgress: ((Float) -> Unit)? = null
    var panelH = 0
        private set
    var progress = 0f
        private set
    private val scrim = View(ctx).apply { setBackgroundColor(0x88000000.toInt()); alpha = 0f }
    private val holder = FrameLayout(ctx)
    private var anim: SpringAnimation? = null
    private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val prop = object : FloatPropertyCompat<PanelLayer>("progress") {
        override fun getValue(o: PanelLayer): Float = o.progress
        override fun setValue(o: PanelLayer, v: Float) { o.setProg(v) }
    }

    init {
        addView(scrim, flp(MATCH, MATCH))
        holder.setPadding(ctx.dpi(12f), topInset + ctx.dpi(6f), ctx.dpi(12f), ctx.dpi(12f))
        holder.addView(content, flp(MATCH, content.layoutParams?.height ?: WRAP))
        addView(holder, flp(MATCH, WRAP, Gravity.TOP))
    }

    fun prepare(screenW: Int, screenH: Int) {
        holder.measure(MeasureSpec.makeMeasureSpec(screenW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST))
        panelH = max(1, holder.measuredHeight)
        setProg(0f)
    }

    private fun setProg(p: Float) {
        progress = p.coerceIn(0f, 1.03f)
        holder.translationY = -panelH * (1f - progress)
        scrim.alpha = progress.coerceIn(0f, 1f)
        onProgress?.invoke(progress)
    }

    fun dragTo(dy: Float) { anim?.cancel(); setProg(dy / panelH) }

    fun settle(open: Boolean, velocity: Float) {
        anim?.cancel()
        val target = if (open) 1f else 0f
        if (!Fx.spring) { setProg(target); if (!open) onClosed(); return }
        val a = SpringAnimation(this, prop)
        a.setSpring(SpringForce(target).setStiffness(420f * Prefs.animSpeed).setDampingRatio(if (open) 0.82f else 1f))
        a.setStartVelocity(velocity / panelH)
        a.setMinimumVisibleChange(0.002f)
        a.addEndListener { _, canceled, _, _ -> if (!canceled && !open) onClosed() }
        anim = a
        a.start()
    }

    /** Lets a view (e.g. a grab handle) drag the panel closed with an upward swipe. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun attachDismiss(v: View) {
        var sy = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sy = e.rawY; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
                MotionEvent.ACTION_MOVE -> { vt?.addMovement(e); dragTo(panelH + (e.rawY - sy)) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    settle(!(progress < 0.75f || vy < -900f), vy)
                    vt?.recycle(); vt = null
                }
            }
            true
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!closeAnywhere) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> if (!dragging && e.y - downY < -slop * 2 && progress > 0.9f) {
                dragging = true
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; return true }
            MotionEvent.ACTION_MOVE -> if (dragging) dragTo(panelH + (e.y - downY))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    settle(!(progress < 0.75f || vy < -900f), vy)
                } else if (e.actionMasked == MotionEvent.ACTION_UP && e.y > holder.translationY + holder.height) {
                    settle(false, 0f)
                }
                dragging = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }
}
