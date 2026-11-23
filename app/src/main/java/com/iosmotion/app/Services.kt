package com.iosmotion.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MotionAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: MotionAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    fun lockScreen(): Boolean = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
}

class HandleView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val pw = context.dp(44f)
        val ph = context.dp(5f)
        val l = (w - pw) / 2f
        val t = context.dp(7f)
        val o = context.dp(1.5f)
        p.color = 0x44000000
        c.drawRoundRect(l - o, t - o, l + pw + o, t + ph + o, ph, ph, p)
        p.color = 0xEBFFFFFF.toInt()
        c.drawRoundRect(l, t, l + pw, t + ph, ph / 2, ph / 2, p)
    }
}

class OverlayService : Service(), PanelHost {
    companion object {
        @Volatile var running = false
        const val ACTION_STOP = "com.iosmotion.app.STOP"
        private const val CH = "overlay"
        fun start(c: Context) { try { ContextCompat.startForegroundService(c, Intent(c, OverlayService::class.java)) } catch (_: Exception) {} }
        fun stop(c: Context) { c.stopService(Intent(c, OverlayService::class.java)) }
    }

    private lateinit var wm: WindowManager
    private lateinit var ui: Context
    private val main = Handler(Looper.getMainLooper())
    private var left: View? = null
    private var right: View? = null
    private var panel: PanelLayer? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "active" || key == "gestures") main.post { if (!Fx.gestures) stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ui = android.view.ContextThemeWrapper(this, R.style.Theme_IOSMotion)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, L.t("Gesture overlay", "Hareket katmanı"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_sliders)
            .setContentTitle("iOS MOTION 26")
            .setContentText(L.t("Gesture handles are active", "Hareket tutamaçları etkin"))
            .setContentIntent(open)
            .addAction(0, L.t("Stop", "Durdur"), stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 26, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (!Settings.canDrawOverlays(this) || !Fx.gestures) { stopSelf(); return }
        Prefs.listen(prefListener)
        val sb = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else dpi(24f) }
        try {
            left = addHandle(0, true, sb)
            right = addHandle(1, false, sb)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun dpi(v: Float) = ui.dpi(v)

    private fun addHandle(kind: Int, isLeft: Boolean, sb: Int): View {
        val v = HandleView(ui)
        val slop = ViewConfiguration.get(ui).scaledTouchSlop
        var downY = 0f
        var dragging = false
        var vt: VelocityTracker? = null
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; dragging = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - downY
                    if (!dragging && dy > slop * 2) { dragging = true; Haptics.tick(v); openPanel(kind) }
                    if (dragging) panel?.dragTo(dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    if (dragging) panel?.let { it.settle(it.progress > 0.35f || vy > 900f, vy) }
                    else if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.rawY - downY) < slop) { Haptics.tick(v); openPanel(kind); panel?.settle(true, 0f) }
                    vt?.recycle(); vt = null
                }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            dpi(120f), dpi(28f), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or (if (isLeft) Gravity.START else Gravity.END)
        lp.x = dpi(8f)
        lp.y = sb
        wm.addView(v, lp)
        return v
    }

    private fun openPanel(kind: Int) {
        removePanel()
        val dm = resources.displayMetrics
        val sb = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else dpi(24f) }
        var anywhere = true
        val content: View
        if (kind == 1) {
            content = ControlCenterView(ui, this, null)
        } else {
            anywhere = false
            val box = GlassView(ui).apply { corner = ui.dp(30f); setPadding(dpi(16f), dpi(16f), dpi(16f), 0) }
            val col = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
            val sv = ScrollView(ui).apply { isVerticalScrollBarEnabled = false }
            sv.addView(NotificationListView(ui, this, null), FrameLayout.LayoutParams(MATCH, WRAP))
            col.addView(sv, llp(MATCH, 0, 1f))
            val grab = HandleView(ui)
            col.addView(grab, llp(MATCH, dpi(30f)))
            box.addView(col, flp(MATCH, MATCH))
            box.layoutParams = FrameLayout.LayoutParams(MATCH, (dm.heightPixels * 0.72f).toInt())
            content = box
            grabHandle = grab
        }
        val layer = PanelLayer(ui, content, sb, anywhere) { removePanel() }
        grabHandle?.let { layer.attachDismiss(it) }
        grabHandle = null
        val blurOn = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled && Fx.blur
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (blurOn) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val lp = WindowManager.LayoutParams(MATCH, MATCH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        layer.prepare(dm.widthPixels, dm.heightPixels)
        if (blurOn) {
            val maxR = (6f + Prefs.blurIntensity / 100f * 40f) * dm.density
            var lastR = -10
            layer.onProgress = { p ->
                val r = (maxR * p.coerceIn(0f, 1f)).toInt()
                if (abs(r - lastR) >= 4 || r == 0) {
                    lastR = r
                    if (Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = r
                    try { wm.updateViewLayout(layer, lp) } catch (_: Exception) {}
                }
            }
        }
        try { wm.addView(layer, lp); panel = layer } catch (_: Exception) { toast(L.t("Overlay permission missing", "Katman izni eksik")) }
    }

    private var grabHandle: View? = null

    private fun removePanel() {
        panel?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        panel = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { Prefs.unlisten(prefListener) } catch (_: Exception) {}
        removePanel()
        left?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        right?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    override fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(intent) } catch (_: Exception) { toast(L.t("Cannot open", "Açılamadı")) }
        panel?.settle(false, 0f)
    }

    override fun toast(msg: String) { Toast.makeText(ui, msg, Toast.LENGTH_SHORT).show() }
    override fun dismissPanel() { panel?.settle(false, 0f) }
}
