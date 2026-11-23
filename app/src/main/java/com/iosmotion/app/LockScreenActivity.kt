package com.iosmotion.app

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ClockView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD); color = Color.WHITE }
    private var text = "00:00"
    private var prev = "00:00"
    private var t = 1f
    private var anim: ValueAnimator? = null

    fun setTime(s: String) {
        if (s == text) return
        prev = text
        text = s
        anim?.cancel()
        if (!Fx.transitions) { t = 1f; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Fx.dur(650L)
            interpolator = PathInterpolator(0.2f, 0.9f, 0.2f, 1f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(wm: Int, hm: Int) { setMeasuredDimension(resolveSize(context.dpi(300f), wm), context.dpi(150f)) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.textSize = min(context.dp(128f), w / 3.0f)
        paint.shader = LinearGradient(0f, 0f, 0f, h, 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), Shader.TileMode.CLAMP)
        val digit = paint.measureText("0")
        val colon = paint.measureText(":")
        val total = digit * 4 + colon
        var x = (w - total) / 2f
        val base = h * 0.82f
        val travel = context.dp(46f)
        for (i in text.indices) {
            val ch = text[i].toString()
            val cw = if (text[i] == ':') colon else digit
            val old = if (i < prev.length) prev[i].toString() else ch
            val cx = x + (cw - paint.measureText(ch)) / 2f
            if (t < 1f && old != ch) {
                paint.alpha = ((1f - t) * 255).toInt()
                c.drawText(old, x + (cw - paint.measureText(old)) / 2f, base - t * travel, paint)
                paint.alpha = (t * 255).toInt()
                c.drawText(ch, cx, base + (1f - t) * travel, paint)
                paint.alpha = 255
            } else c.drawText(ch, cx, base, paint)
            x += cw
        }
    }
}

object WeatherRepo {
    data class W(val temp: Float, val code: Int)

    fun cached(): W? = if (Prefs.wTime > 0L) W(Prefs.wTemp, Prefs.wCode) else null

    fun describe(code: Int): String = when (code) {
        0 -> L.t("Clear", "Açık")
        1, 2 -> L.t("Mostly clear", "Çoğunlukla açık")
        3 -> L.t("Overcast", "Kapalı")
        45, 48 -> L.t("Fog", "Sis")
        in 51..57 -> L.t("Drizzle", "Çise")
        in 61..67 -> L.t("Rain", "Yağmur")
        in 71..77 -> L.t("Snow", "Kar")
        in 80..82 -> L.t("Showers", "Sağanak")
        in 95..99 -> L.t("Thunderstorm", "Fırtına")
        else -> L.t("Weather", "Hava")
    }

    /** err: "permission", "location" or "network". */
    fun fetch(c: Context, done: (W?, String?) -> Unit) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { done(null, "permission"); return }
        val lm = c.getSystemService(LocationManager::class.java)
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val l = lm.getLastKnownLocation(p)
                val b = best
                if (l != null && (b == null || l.time > b.time)) best = l
            } catch (_: Exception) {
            }
        }
        val found = best
        if (found != null) { request(found, done); return }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, c.mainExecutor) { loc -> if (loc != null) request(loc, done) else done(null, "location") }
                return
            } catch (_: Exception) {
            }
        }
        done(null, "location")
    }

    private fun request(loc: Location, done: (W?, String?) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        Thread {
            var w: W? = null
            try {
                val u = URL(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code&timezone=auto", loc.latitude, loc.longitude))
                val con = u.openConnection() as HttpURLConnection
                con.connectTimeout = 8000
                con.readTimeout = 8000
                val body = con.inputStream.bufferedReader().use { it.readText() }
                con.disconnect()
                val cur = JSONObject(body).getJSONObject("current")
                val got = W(cur.getDouble("temperature_2m").toFloat(), cur.getInt("weather_code"))
                Prefs.wTemp = got.temp; Prefs.wCode = got.code; Prefs.wTime = System.currentTimeMillis()
                w = got
            } catch (_: Exception) {
            }
            val res = w
            h.post { if (res != null) done(res, null) else done(null, "network") }
        }.start()
    }
}

class LockScreenActivity : AppCompatActivity() {
    private lateinit var clock: ClockView
    private lateinit var dateTv: TextView
    private lateinit var wTemp: TextView
    private lateinit var wDesc: TextView
    private lateinit var bPct: TextView
    private lateinit var bInfo: TextView
    private lateinit var torchFill: View
    private lateinit var torchIcon: ImageView
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var wallpaper: WallpaperView
    private val handler = Handler(Looper.getMainLooper())
    private var torchCb: CameraManager.TorchCallback? = null
    private var vt: VelocityTracker? = null
    private var downY = 0f

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60_000L - now % 60_000L + 50L)
        }
    }
    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadWeather() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ic = WindowInsetsControllerCompat(window, window.decorView)
        ic.hide(WindowInsetsCompat.Type.systemBars())
        ic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val c = this
        root = FrameLayout(c)
        wallpaper = WallpaperView(c).apply { forceDark = true }
        root.addView(wallpaper, flp(MATCH, MATCH))
        content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24f), dpi(64f), dpi(24f), dpi(28f))
        }
        root.addView(content, flp(MATCH, MATCH))

        dateTv = Ui.tv(c, "", 19f, Color.WHITE, Typeface.NORMAL, "sans-serif-medium")
        content.addView(dateTv)
        clock = ClockView(c)
        content.addView(clock, llp(MATCH, c.dpi(150f), t = c.dpi(2f)))

        val widgets = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val wc = glass(c)
        val wi = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        wi.addView(Ui.icon(c, R.drawable.ic_cloud, 26f, Color.WHITE))
        wTemp = Ui.tv(c, "--°", 32f, Color.WHITE, Typeface.BOLD)
        wDesc = Ui.tv(c, "", 13f, 0xCCFFFFFF.toInt())
        wi.addView(wTemp, llp(WRAP, WRAP, t = c.dpi(6f)))
        wi.addView(wDesc)
        wc.addView(wi)
        wc.setOnClickListener { Haptics.tick(it); loadWeather(true) }
        val bc = glass(c)
        val bi = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        bi.addView(Ui.icon(c, R.drawable.ic_battery, 26f, Color.WHITE))
        bPct = Ui.tv(c, "--%", 32f, Color.WHITE, Typeface.BOLD)
        bInfo = Ui.tv(c, "", 13f, 0xCCFFFFFF.toInt())
        bi.addView(bPct, llp(WRAP, WRAP, t = c.dpi(6f)))
        bi.addView(bInfo)
        bc.addView(bi)
        widgets.addView(wc, llp(0, c.dpi(130f), 1f, r = c.dpi(8f)))
        widgets.addView(bc, llp(0, c.dpi(130f), 1f, l = c.dpi(8f)))
        content.addView(widgets, llp(MATCH, WRAP, t = c.dpi(24f)))

        content.addView(View(c), llp(MATCH, 0, 1f))

        val bottom = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tb = GlassView(c).apply { backdrop = wallpaper; forceDark = true; corner = c.dp(32f) }
        torchFill = View(c).apply { background = Ui.oval(Color.WHITE); alpha = 0f }
        torchIcon = Ui.icon(c, R.drawable.ic_flashlight, 26f, Color.WHITE)
        tb.addView(torchFill, flp(MATCH, MATCH))
        tb.addView(torchIcon, flp(c.dpi(26f), c.dpi(26f), Gravity.CENTER))
        tb.setOnClickListener { Haptics.confirm(it); if (!Torch.toggle(c)) android.widget.Toast.makeText(c, L.t("Flashlight unavailable", "Fener kullanılamıyor"), android.widget.Toast.LENGTH_SHORT).show() }
        Motion.elastic(tb)
        val cb = GlassView(c).apply { backdrop = wallpaper; forceDark = true; corner = c.dp(32f) }
        cb.addView(Ui.icon(c, R.drawable.ic_camera, 26f, Color.WHITE), flp(c.dpi(26f), c.dpi(26f), Gravity.CENTER))
        cb.setOnClickListener {
            Haptics.confirm(it)
            try { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) } catch (_: Exception) {}
        }
        Motion.elastic(cb)
        bottom.addView(tb, llp(c.dpi(64f), c.dpi(64f)))
        bottom.addView(View(c), llp(0, 1, 1f))
        bottom.addView(cb, llp(c.dpi(64f), c.dpi(64f)))
        content.addView(bottom, llp(MATCH, WRAP))

        val hint = Ui.tv(c, L.t("Swipe up to close", "Kapatmak için yukarı kaydırın"), 12f, 0x99FFFFFF.toInt())
        content.addView(hint, llp(WRAP, WRAP, t = c.dpi(14f)))
        val pill = View(c).apply { background = Ui.round(0xCCFFFFFF.toInt(), c.dp(3f)) }
        content.addView(pill, llp(c.dpi(130f), c.dpi(5f), t = c.dpi(10f)))
        setContentView(root)
        Motion.enter(content, 0L, 40f)
        WallpaperStore.ensureLoaded(c) { wallpaper.invalidate() }
    }

    private fun glass(c: Context): GlassView = GlassView(c).also {
        it.backdrop = wallpaper
        it.forceDark = true
        it.corner = c.dp(26f)
        it.setPadding(c.dpi(16f), c.dpi(14f), c.dpi(16f), c.dpi(14f))
    }

    private fun updateTime() {
        val loc = L.locale()
        val d = Date()
        clock.setTime(SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH:mm" else "hh:mm", loc).format(d))
        dateTv.text = SimpleDateFormat(DateFormat.getBestDateTimePattern(loc, "EEEEMMMMd"), loc).format(d)
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val pct = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / max(1, bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            val st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            bPct.text = "$pct%"
            bInfo.text = if (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL) L.t("Charging", "Şarj oluyor") else L.t("Battery", "Pil")
        }
    }

    private fun loadWeather(userTap: Boolean = false) {
        WeatherRepo.cached()?.let { showWeather(it) }
        WeatherRepo.fetch(this) { w, err ->
            if (w != null) { showWeather(w) } else { weatherError(err, userTap) }
        }
    }

    private fun weatherError(err: String?, userTap: Boolean) {
        if (err == "permission") {
            wDesc.text = L.t("Tap to allow location", "Konum için dokunun")
            if (userTap) locPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else if (err == "location") {
            wDesc.text = L.t("Location unavailable", "Konum alınamadı")
        } else if (WeatherRepo.cached() == null) {
            wDesc.text = L.t("No connection", "Bağlantı yok")
        }
    }

    private fun showWeather(w: WeatherRepo.W) {
        wTemp.text = w.temp.roundToInt().toString() + "°"
        wDesc.text = WeatherRepo.describe(w.code)
    }

    override fun onStart() {
        super.onStart()
        updateTime()
        handler.postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L + 50L)
        GlassEngine.start(this, 60f)
        torchCb = Torch.register(this) { on ->
            torchFill.animate().alpha(if (on) 1f else 0f).setDuration(Fx.dur(200L)).start()
            torchIcon.setColorFilter(if (on) 0xFF1C1C1E.toInt() else Color.WHITE)
        }
        loadWeather()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        GlassEngine.stop()
        Torch.unregister(this, torchCb)
    }

    private fun close() {
        if (!Fx.transitions) { finish(); return }
        root.animate().translationY(-root.height.toFloat()).alpha(0f).setDuration(Fx.dur(280L)).withEndAction { finish() }.start()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; vt?.recycle(); vt = VelocityTracker.obtain() }
        }
        vt?.addMovement(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            vt?.computeCurrentVelocity(1000)
            val vy = vt?.yVelocity ?: 0f
            val vx = vt?.xVelocity ?: 0f
            if ((vy < -1200f && abs(vy) > abs(vx) * 1.5f) || e.rawY - downY < -dpi(160f)) { Haptics.confirm(root); close() }
            vt?.recycle(); vt = null
        }
        return super.dispatchTouchEvent(e)
    }
}
