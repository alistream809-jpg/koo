package com.iosmotion.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Property
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.Locale
import kotlin.math.min
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        applyNightMode()
    }

    companion object {
        fun applyNightMode() {
            AppCompatDelegate.setDefaultNightMode(
                when (Prefs.theme) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}

// ---------------------------------------------------------------- Preferences
class BoolP(private val k: String, private val d: Boolean) : ReadWriteProperty<Any?, Boolean> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = Prefs.sp.getBoolean(k, d)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { Prefs.sp.edit().putBoolean(k, value).apply() }
}
class IntP(private val k: String, private val d: Int) : ReadWriteProperty<Any?, Int> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = Prefs.sp.getInt(k, d)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { Prefs.sp.edit().putInt(k, value).apply() }
}
class FloatP(private val k: String, private val d: Float) : ReadWriteProperty<Any?, Float> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Float = Prefs.sp.getFloat(k, d)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) { Prefs.sp.edit().putFloat(k, value).apply() }
}
class LongP(private val k: String, private val d: Long) : ReadWriteProperty<Any?, Long> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Long = Prefs.sp.getLong(k, d)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) { Prefs.sp.edit().putLong(k, value).apply() }
}
class StrP(private val k: String, private val d: String?) : ReadWriteProperty<Any?, String?> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): String? = Prefs.sp.getString(k, d)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
        val e = Prefs.sp.edit()
        if (value == null) e.remove(k) else e.putString(k, value)
        e.apply()
    }
}

object Prefs {
    lateinit var sp: SharedPreferences
    private val keep = emptySet<String>()
    fun init(c: Context) { sp = c.applicationContext.getSharedPreferences("ios_motion_26", Context.MODE_PRIVATE) }
    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)
    fun reset() {
        val e = sp.edit()
        for (k in sp.all.keys.toList()) if (k !in keep) e.remove(k)
        e.apply()
    }

    var active by BoolP("active", false)
    var liquidGlass by BoolP("liquidGlass", true)
    var blur by BoolP("blur", true)
    var reflections by BoolP("reflections", true)
    var spring by BoolP("spring", true)
    var gestures by BoolP("gestures", true)
    var transitions by BoolP("transitions", true)
    var haptics by BoolP("haptics", true)
    var animSpeed by FloatP("animSpeed", 1f)
    var blurIntensity by IntP("blurIntensity", 60)
    var glassAlpha by IntP("glassAlpha", 45)
    var reflectionIntensity by IntP("reflectionIntensity", 60)
    var fps by IntP("fps", 0)
    var theme by IntP("theme", 0)
    var language by StrP("language", "system")
    var wallpaperUri by StrP("wallpaperUri", null)
    var wTemp by FloatP("wTemp", 0f)
    var wCode by IntP("wCode", -1)
    var wTime by LongP("wTime", 0L)
}

/** Effective feature flags: a feature only has effect while the app is activated. */
object Fx {
    val liquid get() = Prefs.active && Prefs.liquidGlass
    val blur get() = liquid && Prefs.blur
    val reflections get() = liquid && Prefs.reflections
    val spring get() = Prefs.active && Prefs.spring
    val transitions get() = Prefs.active && Prefs.transitions
    val gestures get() = Prefs.active && Prefs.gestures
    fun dur(ms: Long): Long = (ms / Prefs.animSpeed).toLong().coerceAtLeast(1L)
}

object L {
    fun isTr(): Boolean = when (Prefs.language) {
        "tr" -> true
        "en" -> false
        else -> Locale.getDefault().language == "tr"
    }
    fun locale(): Locale = when (Prefs.language) {
        "tr" -> Locale("tr")
        "en" -> Locale.ENGLISH
        else -> Locale.getDefault()
    }
    fun t(en: String, tr: String): String = if (isTr()) tr else en
}

object Palette {
    val accent = 0xFF0A84FF.toInt()
    val green = 0xFF34C759.toInt()
    val orange = 0xFFFF9F0A.toInt()
    val red = 0xFFFF453A.toInt()
    fun dark(c: Context): Boolean = when (Prefs.theme) {
        1 -> false
        2 -> true
        else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    fun text(c: Context): Int = if (dark(c)) 0xFFFFFFFF.toInt() else 0xFF0B0D12.toInt()
    fun text2(c: Context): Int = if (dark(c)) 0xB8FFFFFF.toInt() else 0x990B0D12.toInt()
}

// ---------------------------------------------------------------- UI helpers
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun llp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }
fun flp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)

interface PanelHost {
    fun launch(intent: Intent)
    fun toast(msg: String)
    fun dismissPanel()
}

object Ui {
    fun tv(c: Context, s: CharSequence, sp: Float, color: Int, style: Int = Typeface.NORMAL, family: String = "sans-serif"): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            typeface = Typeface.create(family, style)
            includeFontPadding = false
        }

    fun icon(c: Context, res: Int, sizeDp: Float, color: Int): ImageView = ImageView(c).apply {
        setImageResource(res)
        setColorFilter(color, PorterDuff.Mode.SRC_IN)
        layoutParams = LinearLayout.LayoutParams(c.dpi(sizeDp), c.dpi(sizeDp))
    }

    fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = r }
    fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
}

object Haptics {
    fun tick(v: View) = go(v.context, false)
    fun confirm(v: View) = go(v.context, true)
    private fun go(c: Context, strong: Boolean) {
        if (!Prefs.haptics) return
        try {
            val vib = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vib.hasVibrator()) return
            val e = if (Build.VERSION.SDK_INT >= 29)
                VibrationEffect.createPredefined(if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
            else VibrationEffect.createOneShot(if (strong) 18L else 8L, VibrationEffect.DEFAULT_AMPLITUDE)
            vib.vibrate(e)
        } catch (_: Throwable) {
        }
    }
}

// ---------------------------------------------------------------- Motion (springs)
object Motion {
    private val running = java.util.WeakHashMap<View, HashMap<String, Any>>()

    private fun key(p: DynamicAnimation.ViewProperty): String = when (p) {
        DynamicAnimation.TRANSLATION_X -> "tx"
        DynamicAnimation.TRANSLATION_Y -> "ty"
        DynamicAnimation.SCALE_X -> "sx"
        DynamicAnimation.SCALE_Y -> "sy"
        else -> "a"
    }

    private fun prop(p: DynamicAnimation.ViewProperty): Property<View, Float> = when (p) {
        DynamicAnimation.TRANSLATION_X -> View.TRANSLATION_X
        DynamicAnimation.TRANSLATION_Y -> View.TRANSLATION_Y
        DynamicAnimation.SCALE_X -> View.SCALE_X
        DynamicAnimation.SCALE_Y -> View.SCALE_Y
        else -> View.ALPHA
    }

    private fun setNow(v: View, p: DynamicAnimation.ViewProperty, t: Float) {
        when (key(p)) {
            "tx" -> v.translationX = t
            "ty" -> v.translationY = t
            "sx" -> v.scaleX = t
            "sy" -> v.scaleY = t
            else -> v.alpha = t
        }
    }

    fun cancel(v: View, p: DynamicAnimation.ViewProperty) {
        val m = running[v] ?: return
        val a = m.remove(key(p))
        if (a is SpringAnimation) a.cancel() else if (a is Animator) a.cancel()
    }

    fun to(
        v: View, p: DynamicAnimation.ViewProperty, target: Float,
        stiffness: Float = 380f, damping: Float = 0.78f, velocity: Float = 0f, end: (() -> Unit)? = null
    ) {
        cancel(v, p)
        if (!Fx.transitions) { setNow(v, p, target); end?.invoke(); return }
        val map = running.getOrPut(v) { HashMap() }
        if (Fx.spring && p !== DynamicAnimation.ALPHA) {
            val a = SpringAnimation(v, p)
            a.setSpring(SpringForce(target).setStiffness(stiffness * Prefs.animSpeed).setDampingRatio(damping))
            if (velocity != 0f) a.setStartVelocity(velocity)
            a.addEndListener { _, canceled, _, _ -> if (!canceled) end?.invoke() }
            map[key(p)] = a
            a.start()
        } else {
            val a = ObjectAnimator.ofFloat(v, prop(p), target)
            a.duration = Fx.dur(280)
            a.interpolator = DecelerateInterpolator(1.6f)
            var cancelled = false
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) end?.invoke() }
            })
            map[key(p)] = a
            a.start()
        }
    }

    fun reset(v: View) {
        for (p in listOf(DynamicAnimation.TRANSLATION_X, DynamicAnimation.TRANSLATION_Y, DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y, DynamicAnimation.ALPHA)) cancel(v, p)
        v.translationX = 0f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
    }

    @SuppressLint("ClickableViewAccessibility")
    fun elastic(v: View, down: Float = 0.94f) {
        v.setOnTouchListener { view, e ->
            if (view.isEnabled) {
                val s = when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> down
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
                    else -> -1f
                }
                if (s > 0f) {
                    to(view, DynamicAnimation.SCALE_X, s, 900f, 0.6f)
                    to(view, DynamicAnimation.SCALE_Y, s, 900f, 0.6f)
                }
            }
            false
        }
    }

    fun enter(v: View, delayMs: Long = 0, dy: Float = 28f) {
        if (!Fx.transitions) return
        v.alpha = 0f
        v.translationY = v.context.dp(dy)
        v.scaleX = 0.98f
        v.scaleY = 0.98f
        v.postDelayed({
            to(v, DynamicAnimation.ALPHA, 1f)
            to(v, DynamicAnimation.TRANSLATION_Y, 0f)
            to(v, DynamicAnimation.SCALE_X, 1f)
            to(v, DynamicAnimation.SCALE_Y, 1f)
        }, delayMs)
    }
}

// ---------------------------------------------------------------- Torch
object Torch {
    private var cached: String? = null
    var isOn = false
        private set

    private fun id(c: Context): String? {
        cached?.let { return it }
        val cm = c.getSystemService(CameraManager::class.java) ?: return null
        try {
            for (i in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(i)
                val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val back = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                if (flash && back) { cached = i; return i }
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun toggle(c: Context): Boolean {
        val cm = c.getSystemService(CameraManager::class.java) ?: return false
        val i = id(c) ?: return false
        return try { cm.setTorchMode(i, !isOn); true } catch (_: Exception) { false }
    }

    fun register(c: Context, cb: (Boolean) -> Unit): CameraManager.TorchCallback? {
        val cm = c.getSystemService(CameraManager::class.java) ?: return null
        val tc = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id(c)) { isOn = enabled; cb(enabled) }
            }
        }
        return try { cm.registerTorchCallback(tc, Handler(Looper.getMainLooper())); tc } catch (_: Exception) { null }
    }

    fun unregister(c: Context, tc: CameraManager.TorchCallback?) {
        if (tc == null) return
        try { c.getSystemService(CameraManager::class.java)?.unregisterTorchCallback(tc) } catch (_: Exception) {}
    }
}

// ---------------------------------------------------------------- Glass engine (frame clock, tilt sensor)
object GlassEngine : Choreographer.FrameCallback, SensorEventListener {
    private val views = ArrayList<View>()
    private var started = false
    private var posted = false
    private var last = 0L
    private var sm: SensorManager? = null
    private var sensorOn = false
    private var tx = 0f
    private var ty = 0f
    var timeSec = 0f
        private set
    var tiltX = 0f
        private set
    var tiltY = 0f
        private set
    var displayHz = 60f

    fun register(v: View) { if (!views.contains(v)) views.add(v) }
    fun unregister(v: View) { views.remove(v) }

    fun invalidateAll() {
        for (i in views.indices) {
            val v = views[i]
            if (v is GlassView) v.applyFx()
            v.invalidate()
        }
    }

    fun onScroll() { if (Fx.blur) invalidateAll() }

    fun start(c: Context, hz: Float) {
        displayHz = hz
        started = true
        sm = c.getSystemService(SensorManager::class.java)
        sync()
    }

    fun stop() {
        started = false
        setSensor(false)
        Choreographer.getInstance().removeFrameCallback(this)
        posted = false
    }

    fun sync() {
        val run = started && Fx.reflections
        setSensor(run)
        if (run && !posted) { posted = true; Choreographer.getInstance().postFrameCallback(this) }
        invalidateAll()
    }

    fun ambientFps(): Int = if (Prefs.fps == 0) 30 else min(Prefs.fps.toFloat(), displayHz).toInt()

    override fun doFrame(frameTimeNanos: Long) {
        posted = false
        if (!started || !Fx.reflections) return
        timeSec = frameTimeNanos / 1_000_000_000f
        tiltX += (tx - tiltX) * 0.12f
        tiltY += (ty - tiltY) * 0.12f
        val interval = 1_000_000_000L / ambientFps()
        if (frameTimeNanos - last >= interval * 9 / 10) { last = frameTimeNanos; invalidateAll() }
        posted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun setSensor(on: Boolean) {
        if (on == sensorOn) return
        sensorOn = on
        val m = sm ?: return
        if (on) {
            val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            m.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        } else m.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        tx = (-e.values[0] / 9.81f).coerceIn(-1f, 1f)
        ty = (e.values[1] / 9.81f).coerceIn(-1f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
