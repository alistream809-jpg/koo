package com.iosmotion.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.FrameMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs
import kotlin.math.roundToInt

data class StatusItem(val title: String, val ok: Boolean, val detail: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

abstract class Screen(protected val act: MainActivity) {
    val scroll = GlassScrollView(act)
    protected val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    init { scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP)) }
    open fun refresh() {}
    fun setInsets(top: Int, bottom: Int) { scroll.setPadding(act.dpi(20f), top + act.dpi(12f), act.dpi(20f), bottom) }
}

// ================================================================ Dashboard
class DashboardScreen(act: MainActivity) : Screen(act) {
    private val statusHolder = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val checkHolder = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private var perfView: TextView? = null
    private var perfLine = ""

    private class Feat(val t: String, val s: String, val get: () -> Boolean, val set: (Boolean) -> Unit, val key: String)

    init {
        val c = act
        col.addView(Ui.tv(c, "iOS MOTION 26", 34f, Palette.text(c), Typeface.BOLD).apply { letterSpacing = -0.02f })
        col.addView(Ui.tv(c, L.t("Liquid Glass engine for One UI", "One UI için Liquid Glass motoru"), 15f, Palette.text2(c)), llp(WRAP, WRAP, t = c.dpi(4f)))
        col.addView(statusHolder, llp(MATCH, WRAP, t = c.dpi(20f)))
        col.addView(featuresCard(), llp(MATCH, WRAP, t = c.dpi(16f)))
        col.addView(checkHolder, llp(MATCH, WRAP, t = c.dpi(16f)))
        col.addView(Ui.tv(c, L.t(
            "Effects apply only while activated. Android does not let apps replace One UI's system animations or secure lock screen; the status list above shows exactly what is active.",
            "Efektler yalnızca etkinken uygulanır. Android, uygulamaların One UI sistem animasyonlarını veya güvenli kilit ekranını değiştirmesine izin vermez; yukarıdaki liste tam olarak nelerin etkin olduğunu gösterir."),
            12f, Palette.text2(c)), llp(MATCH, WRAP, t = c.dpi(16f)))
        refresh()
    }

    private fun featuresCard(): View {
        val c = act
        val feats = listOf(
            Feat(L.t("Liquid Glass", "Liquid Glass"), L.t("Glass surfaces, rim light, edge refraction", "Cam yüzeyler, kenar ışığı, kenar kırılması"), { Prefs.liquidGlass }, { Prefs.liquidGlass = it }, "liquidGlass"),
            Feat(L.t("Blur Effects", "Bulanıklık Efektleri"), L.t("Real-time backdrop blur (Android 12+)", "Gerçek zamanlı arka plan bulanıklığı (Android 12+)"), { Prefs.blur }, { Prefs.blur = it }, "blur"),
            Feat(L.t("Glass Reflections", "Cam Yansımaları"), L.t("Animated highlights, tilt-reactive sheen, ambient motion", "Hareketli parlamalar, eğime duyarlı ışık, ortam hareketi"), { Prefs.reflections }, { Prefs.reflections = it }, "reflections"),
            Feat(L.t("Spring Animations", "Yay Animasyonları"), L.t("Spring physics for buttons, panels and tabs", "Düğmeler, paneller ve sekmeler için yay fiziği"), { Prefs.spring }, { Prefs.spring = it }, "spring"),
            Feat(L.t("Gesture Effects", "Hareket Efektleri"), L.t("Edge handles: left = Notifications, right = Control Center", "Kenar tutamaçları: sol = Bildirimler, sağ = Denetim Merkezi"), { Prefs.gestures }, { Prefs.gestures = it }, "gestures"),
            Feat(L.t("Dynamic Transitions", "Dinamik Geçişler"), L.t("Animated screen transitions and back-gesture preview", "Animasyonlu ekran geçişleri ve geri hareketi önizlemesi"), { Prefs.transitions }, { Prefs.transitions = it }, "transitions")
        )
        val card = Blocks.card(c, act.wallpaper, 26f, 16f)
        val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(Blocks.section(c, L.t("Features", "Özellikler")), llp(WRAP, WRAP, b = c.dpi(4f)))
        feats.forEachIndexed { i, f ->
            if (i > 0) inner.addView(Blocks.divider(c))
            inner.addView(Blocks.switchRow(c, f.t, f.s, f.get()) { v -> f.set(v); act.onFeatureChanged(f.key) })
        }
        card.addView(inner)
        return card
    }

    fun setPerf(s: String) { perfLine = s; perfView?.text = s }

    override fun refresh() {
        val c = act
        statusHolder.removeAllViews()
        val on = Prefs.active
        val card = Blocks.card(c, act.wallpaper, 28f, 18f)
        val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(c).apply { background = Ui.oval(if (on) Palette.green else 0xFF8E8E93.toInt()) }, llp(c.dpi(10f), c.dpi(10f), r = c.dpi(10f)))
        head.addView(Ui.tv(c, if (on) L.t("Active", "Etkin") else L.t("Inactive", "Devre dışı"), 22f, Palette.text(c), Typeface.NORMAL, "sans-serif-medium"))
        inner.addView(head)
        inner.addView(Ui.tv(c, if (on) L.t("Liquid Glass engine is running", "Liquid Glass motoru çalışıyor") else L.t("Effects are off. Press activate.", "Efektler kapalı. Etkinleştir'e basın."), 14f, Palette.text2(c)), llp(WRAP, WRAP, t = c.dpi(6f)))
        perfView = Ui.tv(c, perfLine, 12.5f, Palette.text2(c))
        inner.addView(perfView, llp(WRAP, WRAP, t = c.dpi(10f)))
        val bOn = Blocks.button(c, act.wallpaper, L.t("ACTIVATE iOS 26", "iOS 26 ETKİNLEŞTİR"), Palette.accent) { act.beginActivation() }
        val bOff = Blocks.button(c, act.wallpaper, L.t("DEACTIVATE iOS 26", "iOS 26 DEVRE DIŞI BIRAK")) { act.deactivate() }
        Blocks.enabledLook(bOn, !on)
        Blocks.enabledLook(bOff, on)
        inner.addView(bOn, llp(MATCH, c.dpi(52f), t = c.dpi(14f)))
        inner.addView(bOff, llp(MATCH, c.dpi(52f), t = c.dpi(10f)))
        card.addView(inner)
        statusHolder.addView(card)

        checkHolder.removeAllViews()
        val cc = Blocks.card(c, act.wallpaper, 26f, 16f)
        val ci = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        ci.addView(Blocks.section(c, L.t("Live status", "Canlı durum")), llp(WRAP, WRAP, b = c.dpi(6f)))
        for (it in act.statusItems()) {
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, c.dpi(7f), 0, c.dpi(7f)) }
            row.addView(View(c).apply { background = Ui.oval(if (it.ok) Palette.green else Palette.orange) }, llp(c.dpi(8f), c.dpi(8f), r = c.dpi(12f)))
            val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            t.addView(Ui.tv(c, it.title, 14.5f, Palette.text(c), Typeface.NORMAL, "sans-serif-medium"))
            t.addView(Ui.tv(c, it.detail, 12f, Palette.text2(c)))
            row.addView(t, llp(0, WRAP, 1f))
            if (it.action != null && it.actionLabel != null) {
                val b = Ui.tv(c, it.actionLabel, 13.5f, Palette.accent, Typeface.NORMAL, "sans-serif-medium")
                b.setPadding(c.dpi(10f), c.dpi(8f), 0, c.dpi(8f))
                val a = it.action
                b.setOnClickListener { v -> Haptics.tick(v); a() }
                row.addView(b)
            }
            ci.addView(row)
        }
        cc.addView(ci)
        checkHolder.addView(cc)
    }
}

class ControlScreen(act: MainActivity) : Screen(act) {
    init {
        col.addView(Ui.tv(act, L.t("Control Center", "Denetim Merkezi"), 28f, Palette.text(act), Typeface.BOLD), llp(WRAP, WRAP, b = act.dpi(14f)))
        col.addView(ControlCenterView(act, act, act.wallpaper))
    }
}

class NotifScreen(act: MainActivity) : Screen(act) {
    init { col.addView(NotificationListView(act, act, act.wallpaper)) }
}

class LockTabScreen(act: MainActivity) : Screen(act) {
    init {
        val c = act
        col.addView(Ui.tv(c, L.t("Lock Screen", "Kilit Ekranı"), 28f, Palette.text(c), Typeface.BOLD), llp(WRAP, WRAP, b = c.dpi(14f)))
        val card = Blocks.card(c, act.wallpaper, 26f, 16f)
        val i = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        i.addView(Ui.tv(c, L.t(
            "An iOS-style lock screen interface: large animated clock, date, live weather (Open-Meteo, needs location permission), glass widgets, flashlight and camera shortcuts, animated wallpaper.",
            "iOS tarzı kilit ekranı arayüzü: büyük animasyonlu saat, tarih, canlı hava durumu (Open-Meteo, konum izni gerekir), cam widget'lar, fener ve kamera kısayolları, animasyonlu duvar kağıdı."), 14.5f, Palette.text(c)))
        i.addView(Ui.tv(c, L.t(
            "Limitation: this is an in-app interface. It does NOT replace Samsung's secure lock screen; Android does not allow that without system privileges.",
            "Sınırlama: Bu uygulama içi bir arayüzdür. Samsung'un güvenli kilit ekranının YERİNE GEÇMEZ; Android bunu sistem ayrıcalığı olmadan izin vermez."), 12.5f, Palette.text2(c)), llp(WRAP, WRAP, t = c.dpi(10f)))
        i.addView(Blocks.button(c, act.wallpaper, L.t("Open lock screen interface", "Kilit ekranı arayüzünü aç"), Palette.accent, R.drawable.ic_lock) {
            act.startActivity(Intent(act, LockScreenActivity::class.java))
        }, llp(MATCH, c.dpi(52f), t = c.dpi(14f)))
        card.addView(i)
        col.addView(card)
    }
}

class SettingsScreen(act: MainActivity) : Screen(act) {
    private fun slider(parent: LinearLayout, title: String, init: Float, fmt: (Float) -> String, onV: (Float) -> Unit) {
        val c = act
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val val_ = Ui.tv(c, fmt(init), 14f, Palette.text2(c))
        head.addView(Ui.tv(c, title, 15f, Palette.text(c), Typeface.NORMAL, "sans-serif-medium"), llp(0, WRAP, 1f))
        head.addView(val_)
        val s = GlassSlider(c, GlassSlider.Mode.THIN)
        s.value = init
        s.onChange = { v, _ -> val_.text = fmt(v); onV(v) }
        parent.addView(head, llp(MATCH, WRAP, t = c.dpi(10f)))
        parent.addView(s, llp(MATCH, c.dpi(36f)))
    }

    init {
        val c = act
        col.addView(Ui.tv(c, L.t("Settings", "Ayarlar"), 28f, Palette.text(c), Typeface.BOLD), llp(WRAP, WRAP, b = c.dpi(14f)))

        val look = Blocks.card(c, act.wallpaper, 26f, 16f)
        val li = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        li.addView(Blocks.section(c, L.t("Appearance", "Görünüm")))
        li.addView(Segmented(c, listOf(L.t("System", "Sistem"), L.t("Light", "Açık"), L.t("Dark", "Koyu")), Prefs.theme) { Prefs.theme = it; App.applyNightMode(); act.recreate() }, llp(MATCH, WRAP, t = c.dpi(10f)))
        li.addView(Blocks.section(c, L.t("Language", "Dil")), llp(WRAP, WRAP, t = c.dpi(16f)))
        val langs = listOf("system", "en", "tr")
        li.addView(Segmented(c, listOf(L.t("System", "Sistem"), "English", "Türkçe"), langs.indexOf(Prefs.language).coerceAtLeast(0)) { Prefs.language = langs[it]; act.recreate() }, llp(MATCH, WRAP, t = c.dpi(10f)))
        look.addView(li)
        col.addView(look)

        val glass = Blocks.card(c, act.wallpaper, 26f, 16f)
        val gi = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        gi.addView(Blocks.section(c, L.t("Glass and motion", "Cam ve hareket")))
        slider(gi, L.t("Blur intensity", "Bulanıklık yoğunluğu"), Prefs.blurIntensity / 100f, { "${(it * 100).roundToInt()}%" }) { Prefs.blurIntensity = (it * 100).roundToInt(); GlassEngine.invalidateAll() }
        slider(gi, L.t("Glass transparency", "Cam şeffaflığı"), Prefs.glassAlpha / 100f, { "${(it * 100).roundToInt()}%" }) { Prefs.glassAlpha = (it * 100).roundToInt(); GlassEngine.invalidateAll() }
        slider(gi, L.t("Reflection intensity", "Yansıma yoğunluğu"), Prefs.reflectionIntensity / 100f, { "${(it * 100).roundToInt()}%" }) { Prefs.reflectionIntensity = (it * 100).roundToInt(); GlassEngine.invalidateAll() }
        slider(gi, L.t("Animation speed", "Animasyon hızı"), (Prefs.animSpeed - 0.5f) / 1.5f, { String.format("%.2fx", 0.5f + it * 1.5f) }) {
            Prefs.animSpeed = 0.5f + it * 1.5f
        }
        gi.addView(Blocks.divider(c), llp(MATCH, 1, t = c.dpi(14f)))
        gi.addView(Blocks.switchRow(c, L.t("Haptic feedback", "Titreşimli geri bildirim"), L.t("Short vibration on taps and sliders", "Dokunma ve kaydırıcılarda kısa titreşim"), Prefs.haptics) { Prefs.haptics = it })
        gi.addView(Blocks.section(c, L.t("Frame rate preference", "Kare hızı tercihi")), llp(WRAP, WRAP, t = c.dpi(10f)))
        val rates = act.supportedRates()
        val items = listOf(L.t("Auto", "Otomatik")) + rates.map { "$it Hz" }
        val sel = if (Prefs.fps == 0) 0 else rates.indexOf(Prefs.fps) + 1
        gi.addView(Segmented(c, items, sel.coerceAtLeast(0)) { Prefs.fps = if (it == 0) 0 else rates[it - 1]; act.applyRefreshPref(); GlassEngine.sync() }, llp(MATCH, WRAP, t = c.dpi(10f)))
        gi.addView(Ui.tv(c, L.t("Auto keeps ambient glass animation at 30 fps to save battery. The system may still cap the rate.", "Otomatik, pil tasarrufu için ortam cam animasyonunu 30 fps'te tutar. Sistem hızı yine de sınırlayabilir."), 12f, Palette.text2(c)), llp(WRAP, WRAP, t = c.dpi(8f)))
        glass.addView(gi)
        col.addView(glass, llp(MATCH, WRAP, t = c.dpi(16f)))

        val sys = Blocks.card(c, act.wallpaper, 26f, 16f)
        val si = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        si.addView(Blocks.section(c, L.t("Wallpaper and system", "Duvar kağıdı ve sistem")))
        si.addView(Blocks.button(c, act.wallpaper, L.t("Choose wallpaper image", "Duvar kağıdı seç")) { act.pickWallpaper() }, llp(MATCH, c.dpi(48f), t = c.dpi(10f)))
        si.addView(Blocks.button(c, act.wallpaper, L.t("Remove wallpaper image", "Duvar kağıdını kaldır")) { WallpaperStore.clear(act) { act.refreshVisuals() } }, llp(MATCH, c.dpi(48f), t = c.dpi(8f)))
        si.addView(Blocks.button(c, act.wallpaper, L.t("Battery optimization (keep overlay alive)", "Pil optimizasyonu (katman açık kalsın)")) {
            act.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, llp(MATCH, c.dpi(48f), t = c.dpi(8f)))
        si.addView(Blocks.button(c, act.wallpaper, L.t("Reset settings", "Ayarları sıfırla"), Palette.red) { act.confirmReset() }, llp(MATCH, c.dpi(48f), t = c.dpi(8f)))
        sys.addView(si)
        col.addView(sys, llp(MATCH, WRAP, t = c.dpi(16f)))
    }
}

// ================================================================ Navigation bar
class NavBar(ctx: Context, bd: View, private val onSelect: (Int) -> Unit, private val onSwipeUp: () -> Unit) : GlassView(ctx) {
    private val names = listOf(L.t("Home", "Ana"), L.t("Control", "Kontrol"), L.t("Alerts", "Bildirim"), L.t("Lock", "Kilit"), L.t("Settings", "Ayarlar"))
    private val icons = intArrayOf(R.drawable.ic_home, R.drawable.ic_control, R.drawable.ic_bell, R.drawable.ic_lock, R.drawable.ic_sliders)
    private val imgs = ArrayList<ImageView>()
    private val txts = ArrayList<TextView>()
    private val indicator = View(ctx)
    private var index = 0
    private var cellW = 0
    private var vt: VelocityTracker? = null

    init {
        backdrop = bd
        corner = ctx.dp(34f)
        indicator.background = Ui.round(if (Palette.dark(ctx)) 0x33FFFFFF else 0x22000000, ctx.dp(28f))
        addView(indicator, flp(0, MATCH))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 5) {
            val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            val img = ImageView(ctx).apply { setImageResource(icons[i]) }
            val tx = Ui.tv(ctx, names[i], 10.5f, Palette.text2(ctx), Typeface.NORMAL, "sans-serif-medium")
            cell.addView(img, llp(ctx.dpi(24f), ctx.dpi(24f)))
            cell.addView(tx, llp(WRAP, WRAP, t = ctx.dpi(3f)))
            cell.setOnClickListener { Haptics.tick(it); onSelect(i) }
            Motion.elastic(cell, 0.9f)
            row.addView(cell, llp(0, MATCH, 1f))
            imgs.add(img); txts.add(tx)
        }
        addView(row, flp(MATCH, MATCH))
        paint()
    }

    private fun paint() {
        for (i in 0 until 5) {
            val col = if (i == index) Palette.text(context) else Palette.text2(context)
            imgs[i].setColorFilter(col)
            txts[i].setTextColor(col)
        }
    }

    fun setIndex(i: Int) {
        index = i
        paint()
        Motion.to(indicator, DynamicAnimation.TRANSLATION_X, (i * cellW).toFloat(), 500f, 0.7f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cellW = w / 5
        val m = context.dpi(5f)
        indicator.layoutParams = FrameLayout.LayoutParams(cellW - 2 * m, MATCH).also { it.setMargins(m, m, m, m) }
        indicator.translationX = (index * cellW).toFloat()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = VelocityTracker.obtain() }
        vt?.addMovement(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            vt?.computeCurrentVelocity(1000)
            val vy = vt?.yVelocity ?: 0f
            val vx = vt?.xVelocity ?: 0f
            if (vy < -1000f && abs(vy) > abs(vx) * 1.5f) post { onSwipeUp() }
            vt?.recycle(); vt = null
        }
        return super.dispatchTouchEvent(e)
    }
}

// ================================================================ Activity
class MainActivity : AppCompatActivity(), PanelHost {
    lateinit var wallpaper: WallpaperView
    private lateinit var root: FrameLayout
    private lateinit var contentRoot: FrameLayout
    private lateinit var screenHost: FrameLayout
    private lateinit var nav: NavBar
    private lateinit var dashboard: DashboardScreen
    private val screens = arrayOfNulls<Screen>(5)
    private var current = 0
    private var insetTop = 0
    private var insetBottom = 0
    private var activating = false
    private val asked = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var frames = 0
    private var totalNs = 0L
    private var janky = 0
    private var budgetNs = 16_000_000L

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { continueActivation() }
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            WallpaperStore.set(this, uri) { refreshVisuals() }
        }
    }

    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {}
        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            val s = screens[current]?.scroll ?: return
            if (!Fx.transitions) return
            val p = backEvent.progress
            val sign = if (backEvent.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
            s.translationX = sign * p * dpf(56f)
            s.scaleX = 1f - p * 0.06f
            s.scaleY = s.scaleX
            s.alpha = 1f - p * 0.3f
        }
        override fun handleOnBackPressed() { screens[current]?.scroll?.let { Motion.reset(it) }; showTab(0) }
        override fun handleOnBackCancelled() {
            val s = screens[current]?.scroll ?: return
            Motion.to(s, DynamicAnimation.TRANSLATION_X, 0f)
            Motion.to(s, DynamicAnimation.SCALE_X, 1f)
            Motion.to(s, DynamicAnimation.SCALE_Y, 1f)
            Motion.to(s, DynamicAnimation.ALPHA, 1f)
        }
    }

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, fm, _ ->
        frames++
        val t = fm.getMetric(FrameMetrics.TOTAL_DURATION)
        totalNs += t
        if (t > budgetNs) janky++
    }

    private val perfTick = object : Runnable {
        override fun run() {
            val hz = display().refreshRate
            val avg = if (frames > 0) totalNs / frames / 1_000_000f else 0f
            dashboard.setPerf(L.t("Display ", "Ekran ") + hz.roundToInt() + " Hz  |  " + L.t("rendered ", "çizilen ") + frames + " fps  |  " +
                String.format("%.1f", avg) + " ms  |  " + L.t("over budget ", "bütçe aşan ") + janky)
            frames = 0; totalNs = 0L; janky = 0
            handler.postDelayed(this, 1000L)
        }
    }

    private fun dpf(v: Float) = dp(v)
    private fun display(): Display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        ctl.isAppearanceLightStatusBars = !Palette.dark(this)
        ctl.isAppearanceLightNavigationBars = !Palette.dark(this)

        root = FrameLayout(this)
        contentRoot = FrameLayout(this)
        root.addView(contentRoot, flp(MATCH, MATCH))
        wallpaper = WallpaperView(this)
        contentRoot.addView(wallpaper, flp(MATCH, MATCH))
        screenHost = FrameLayout(this)
        contentRoot.addView(screenHost, flp(MATCH, MATCH))
        nav = NavBar(this, contentRoot, { showTab(it) }, { if (current != 0) showTab(0) })
        root.addView(nav, FrameLayout.LayoutParams(MATCH, dpi(68f), Gravity.BOTTOM).also { it.setMargins(dpi(20f), 0, dpi(20f), dpi(12f)) })
        setContentView(root)

        dashboard = DashboardScreen(this)
        screens[0] = dashboard
        screenHost.addView(dashboard.scroll, flp(MATCH, MATCH))
        current = savedInstanceState?.getInt("tab") ?: 0
        if (current != 0) {
            screenFor(current).scroll.visibility = View.VISIBLE
            dashboard.scroll.visibility = View.GONE
        }
        nav.setIndex(current)
        backCb.isEnabled = current != 0
        onBackPressedDispatcher.addCallback(this, backCb)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetTop = b.top
            insetBottom = b.bottom
            applyInsets()
            insets
        }
        WallpaperStore.ensureLoaded(this) { wallpaper.invalidate() }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt("tab", current) }

    private fun screenFor(i: Int): Screen {
        screens[i]?.let { return it }
        val s: Screen = when (i) {
            1 -> ControlScreen(this)
            2 -> NotifScreen(this)
            3 -> LockTabScreen(this)
            else -> SettingsScreen(this)
        }
        screens[i] = s
        screenHost.addView(s.scroll, flp(MATCH, MATCH))
        s.scroll.visibility = View.GONE
        s.setInsets(insetTop, insetBottom + dpi(112f))
        return s
    }

    private fun applyInsets() {
        (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = insetBottom + dpi(12f)
        nav.requestLayout()
        for (s in screens) s?.setInsets(insetTop, insetBottom + dpi(112f))
    }

    private fun showTab(i: Int) {
        if (i == current && screens[i]?.scroll?.visibility == View.VISIBLE) return
        val old = screens[current]?.scroll
        val nw = screenFor(i).scroll
        val dir = if (i > current) 1f else -1f
        current = i
        nav.setIndex(i)
        backCb.isEnabled = i != 0
        Motion.reset(nw)
        nw.visibility = View.VISIBLE
        if (Fx.transitions) {
            nw.alpha = 0f
            nw.translationX = dir * dpf(48f)
            nw.scaleX = 0.97f
            nw.scaleY = 0.97f
            Motion.to(nw, DynamicAnimation.TRANSLATION_X, 0f)
            Motion.to(nw, DynamicAnimation.ALPHA, 1f)
            Motion.to(nw, DynamicAnimation.SCALE_X, 1f)
            Motion.to(nw, DynamicAnimation.SCALE_Y, 1f)
            if (old != null && old !== nw) {
                Motion.to(old, DynamicAnimation.TRANSLATION_X, -dir * dpf(48f))
                Motion.to(old, DynamicAnimation.ALPHA, 0f) { old.visibility = View.GONE; Motion.reset(old) }
            }
        } else if (old != null && old !== nw) old.visibility = View.GONE
        screens[i]?.refresh()
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onResume() {
        super.onResume()
        val hz = display().refreshRate
        budgetNs = (1.5e9f / hz).toLong()
        GlassEngine.start(this, hz)
        applyRefreshPref()
        window.addOnFrameMetricsAvailableListener(frameListener, handler)
        handler.post(perfTick)
        if (activating) continueActivation()
        if (Prefs.active) {
            if (Fx.gestures && Settings.canDrawOverlays(this) && !OverlayService.running) OverlayService.start(this)
        }
        refreshVisuals()
    }

    override fun onPause() {
        super.onPause()
        GlassEngine.stop()
        try { window.removeOnFrameMetricsAvailableListener(frameListener) } catch (_: Exception) {}
        handler.removeCallbacks(perfTick)
    }

    fun refreshVisuals() {
        GlassEngine.sync()
        wallpaper.invalidate()
        for (s in screens) s?.refresh()
    }

    // ---------------------------------------------------------------- display
    fun supportedRates(): List<Int> {
        val d = display()
        val cur = d.mode
        return d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .map { it.refreshRate.roundToInt() }.distinct().sorted()
    }

    fun applyRefreshPref() {
        val lp = window.attributes
        if (Prefs.fps == 0) {
            lp.preferredDisplayModeId = 0
            lp.preferredRefreshRate = 0f
        } else {
            val d = display()
            val cur = d.mode
            val m = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { abs(it.refreshRate - Prefs.fps) }
            if (m != null && abs(m.refreshRate - Prefs.fps) < 1.5f) lp.preferredDisplayModeId = m.modeId
            lp.preferredRefreshRate = Prefs.fps.toFloat()
        }
        window.attributes = lp
    }

    // ---------------------------------------------------------------- activation
    fun beginActivation() {
        activating = true
        asked.clear()
        continueActivation()
    }

    private fun continueActivation() {
        if (!activating) return
        if (!Settings.canDrawOverlays(this) && asked.add("overlay")) {
            AlertDialog.Builder(this)
                .setTitle(L.t("Overlay permission", "Katman izni"))
                .setMessage(L.t("To show the Control Center and Notification Center handles over other apps, allow \"Display over other apps\" for iOS MOTION 26. You can skip it; in-app features still work.",
                    "Denetim Merkezi ve Bildirim Merkezi tutamaçlarını diğer uygulamaların üstünde göstermek için iOS MOTION 26'ya \"Diğer uygulamaların üzerinde göster\" izni verin. Atlayabilirsiniz; uygulama içi özellikler çalışır."))
                .setCancelable(false)
                .setPositiveButton(L.t("Open settings", "Ayarları aç")) { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                .setNegativeButton(L.t("Skip", "Atla")) { _, _ -> continueActivation() }
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && asked.add("notif")) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        finishActivation()
    }

    private fun finishActivation() {
        activating = false
        Prefs.active = true
        syncOverlay()
        applyRefreshPref()
        refreshVisuals()
        val sb = StringBuilder()
        for (s in statusItems()) sb.append(if (s.ok) "[OK] " else "[--] ").append(s.title).append(": ").append(s.detail).append("\n")
        val b = AlertDialog.Builder(this).setTitle(L.t("Activation report", "Etkinleştirme raporu")).setMessage(sb.toString().trim())
            .setPositiveButton(L.t("OK", "Tamam"), null)
        b.show()
    }

    fun deactivate() {
        OverlayService.stop(this)
        Prefs.active = false
        refreshVisuals()
        toast(L.t("Deactivated.", "Devre dışı bırakıldı."))
    }

    private fun syncOverlay() {
        if (Fx.gestures && Settings.canDrawOverlays(this)) OverlayService.start(this) else OverlayService.stop(this)
    }

    fun onFeatureChanged(key: String) {
        when (key) {
            "gestures" -> if (Prefs.active) syncOverlay()
        }
        refreshVisuals()
    }


    fun statusItems(): List<StatusItem> {
        val sdk = Build.VERSION.SDK_INT
        val l = ArrayList<StatusItem>()
        l += StatusItem(L.t("Liquid Glass renderer", "Liquid Glass işleyici"), Fx.liquid, when {
            !Prefs.active -> L.t("Inactive", "Devre dışı")
            !Prefs.liquidGlass -> L.t("Switched off", "Kapalı")
            sdk >= 31 && Prefs.blur -> L.t("Real-time blur (RenderEffect) + glass shading", "Gerçek zamanlı bulanıklık (RenderEffect) + cam gölgelendirme")
            sdk >= 31 -> L.t("Glass shading, blur switched off", "Cam gölgelendirme, bulanıklık kapalı")
            else -> L.t("Glass shading only (blur needs Android 12+)", "Yalnızca cam gölgelendirme (bulanıklık Android 12+ ister)")
        })
        val winBlur = sdk >= 31 && getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        l += StatusItem(L.t("Window blur (overlay panels)", "Pencere bulanıklığı (katman panelleri)"), winBlur,
            if (winBlur) L.t("Supported and enabled by the system", "Sistem tarafından destekleniyor ve açık") else L.t("Not available: panels use translucency only", "Yok: paneller yalnızca saydamlık kullanır"))
        val ov = Settings.canDrawOverlays(this)
        l += StatusItem(L.t("Draw over other apps", "Diğer uygulamaların üzerinde göster"), ov, if (ov) L.t("Granted", "Verildi") else L.t("Not granted", "Verilmedi"),
            if (ov) null else L.t("Grant", "İzin ver")) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        l += StatusItem(L.t("Gesture overlay service", "Hareket katmanı servisi"), OverlayService.running,
            if (OverlayService.running) L.t("Running (swipe down from the top handles)", "Çalışıyor (üstteki tutamaçlardan aşağı kaydırın)") else L.t("Not running", "Çalışmıyor"))
        val na = NotificationStore.hasAccess(this)
        l += StatusItem(L.t("Notification access", "Bildirim erişimi"), na, if (na) L.t("Granted", "Verildi") else L.t("Not granted (notifications are never read)", "Verilmedi (bildirimler okunmaz)"),
            if (na) null else L.t("Grant", "İzin ver")) { launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        val ws = Settings.System.canWrite(this)
        l += StatusItem(L.t("Modify system settings", "Sistem ayarlarını değiştir"), ws, if (ws) L.t("Granted (brightness, rotation)", "Verildi (parlaklık, döndürme)") else L.t("Not granted", "Verilmedi"),
            if (ws) null else L.t("Grant", "İzin ver")) { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        val ac = MotionAccessibilityService.instance != null
        l += StatusItem(L.t("Accessibility (lock tile)", "Erişilebilirlik (kilit kutucuğu)"), ac, if (ac) L.t("Enabled", "Açık") else L.t("Disabled (only needed for the lock tile)", "Kapalı (yalnızca kilit kutucuğu için gerekir)"),
            if (ac) null else L.t("Open", "Aç")) { launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        return l
    }

    fun pickWallpaper() { pickImage.launch(arrayOf("image/*")) }

    fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(L.t("Reset settings?", "Ayarlar sıfırlansın mı?"))
            .setMessage(L.t("All settings return to defaults and effects are deactivated.", "Tüm ayarlar varsayılana döner ve efektler devre dışı kalır."))
            .setPositiveButton(L.t("Reset", "Sıfırla")) { _, _ ->
                OverlayService.stop(this)
                Prefs.reset()
                App.applyNightMode()
                WallpaperStore.ensureLoaded(this) {}
                recreate()
            }
            .setNegativeButton(L.t("Cancel", "Vazgeç"), null)
            .show()
    }

    // ---------------------------------------------------------------- PanelHost
    override fun launch(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast(L.t("Cannot open", "Açılamadı")) }
    }
    override fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    override fun dismissPanel() {}
}
