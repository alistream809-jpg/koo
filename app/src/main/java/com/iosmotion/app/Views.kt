package com.iosmotion.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ================================================================ Glass surface
open class GlassView(ctx: Context) : FrameLayout(ctx) {
    var corner: Float = ctx.dp(24f)
        set(v) { field = v; rebuild() }
    var backdrop: View? = null
    var tint: Int = 0
    var forceDark: Boolean? = null
    var elev: Float = 8f
        set(v) { field = v; applyFx() }

    private val phase = (Math.random() * 6.28).toFloat()
    private val clip = Path()
    private val ring = Path()
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandP = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rimS: LinearGradient? = null
    private var sheenS: RadialGradient? = null
    private var topS: LinearGradient? = null
    private var bandS: LinearGradient? = null
    private val m = Matrix()
    private var touchX = 0f
    private var touchY = 0f
    private var touchAmt = 0f
    private var touchTarget = 0f
    private var touchAnim: ValueAnimator? = null
    private var rec: RenderNode? = null
    private var blurNode: RenderNode? = null
    private var lastBlur = -1f
    private var blurFailed = false
    private var lastLiquid: Boolean? = null
    private val loc = IntArray(2)
    private val bloc = IntArray(2)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, min(corner, min(view.width, view.height) / 2f))
                outline.alpha = 0.6f
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            outlineAmbientShadowColor = 0x33000000
            outlineSpotShadowColor = 0x44000000
        }
        applyFx()
    }

    fun applyFx() {
        val l = Fx.liquid
        if (lastLiquid == l) return
        lastLiquid = l
        elevation = if (l) context.dp(elev) else context.dp(elev * 0.25f)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); GlassEngine.register(this) }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        GlassEngine.unregister(this)
        touchAnim?.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); rebuild() }

    private fun rebuild() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(corner, min(w, h) / 2f)
        clip.reset()
        clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        val inset = min(context.dp(8f), min(w, h) / 4f)
        val inner = Path()
        val ir = max(r - inset, 0f)
        inner.addRoundRect(inset, inset, w - inset, h - inset, ir, ir, Path.Direction.CW)
        ring.set(clip)
        ring.op(inner, Path.Op.DIFFERENCE)
        rimS = LinearGradient(0f, 0f, w, h,
            intArrayOf(0xCCFFFFFF.toInt(), 0x22FFFFFF, 0x00FFFFFF, 0x66FFFFFF),
            floatArrayOf(0f, 0.3f, 0.62f, 1f), Shader.TileMode.CLAMP)
        topS = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        sheenS = RadialGradient(0f, 0f, max(w, h) * 0.8f, intArrayOf(0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        bandS = LinearGradient(-w * 0.12f, 0f, w * 0.12f, 0f,
            intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rimP.strokeWidth = context.dp(1.1f)
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val dark = forceDark ?: Palette.dark(context)
        val liquid = Fx.liquid
        val a = Prefs.glassAlpha / 100f
        var blurred = false
        if (liquid && Fx.blur && backdrop != null && Build.VERSION.SDK_INT >= 31 && !blurFailed && canvas.isHardwareAccelerated) {
            blurred = drawBackdrop(canvas)
        }
        val alpha = when {
            tint != 0 -> 0.9f
            !liquid -> if (dark) 0.16f else 0.62f
            blurred -> if (dark) 0.05f + a * 0.26f else 0.22f + a * 0.5f
            else -> if (dark) 0.08f + a * 0.30f else 0.34f + a * 0.46f
        }
        fillP.color = ColorUtils.setAlphaComponent(if (tint != 0) tint else Color.WHITE, (alpha.coerceIn(0f, 1f) * 255).toInt())
        canvas.drawPath(clip, fillP)
        if (liquid) {
            topP.shader = topS
            topP.alpha = (255 * (0.35f + 0.65f * Prefs.reflectionIntensity / 100f)).toInt()
            canvas.drawPath(clip, topP)
        }
    }

    @RequiresApi(31)
    private fun drawBackdrop(canvas: Canvas): Boolean {
        val bd = backdrop ?: return false
        if (bd.width == 0 || width == 0) return false
        return try {
            val w = width
            val h = height
            val radius = (3f + Prefs.blurIntensity / 100f * 26f) * context.resources.displayMetrics.density / 2.2f
            val pad = (radius * 2.2f).toInt() + 2
            val rn = rec ?: RenderNode("glass-rec").also { rec = it }
            val bn = blurNode ?: RenderNode("glass-blur").also { blurNode = it }
            getLocationInWindow(loc)
            bd.getLocationInWindow(bloc)
            rn.setPosition(0, 0, w + 2 * pad, h + 2 * pad)
            val rc = rn.beginRecording(w + 2 * pad, h + 2 * pad)
            rc.translate((pad + bloc[0] - loc[0]).toFloat(), (pad + bloc[1] - loc[1]).toFloat())
            bd.draw(rc)
            rn.endRecording()
            bn.setPosition(-pad, -pad, w + pad, h + pad)
            if (radius != lastBlur) {
                bn.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                lastBlur = radius
            }
            val bc = bn.beginRecording(w + 2 * pad, h + 2 * pad)
            bc.drawRenderNode(rn)
            bn.endRecording()
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRenderNode(bn)
            // refraction-inspired rim: sharp, slightly magnified backdrop along the edge band
            canvas.save()
            canvas.clipPath(ring)
            canvas.scale(1f + context.dp(8f) / (w / 2f), 1f + context.dp(8f) / (h / 2f), w / 2f, h / 2f)
            canvas.translate(-pad.toFloat(), -pad.toFloat())
            canvas.drawRenderNode(rn)
            canvas.restore()
            canvas.restore()
            true
        } catch (t: Throwable) {
            blurFailed = true
            false
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val r = min(corner, min(w, h) / 2f)
        val half = rimP.strokeWidth / 2f
        if (!Fx.liquid) {
            rimP.shader = null
            rimP.color = if (forceDark ?: Palette.dark(context)) 0x24FFFFFF else 0x14000000
            canvas.drawRoundRect(half, half, w - half, h - half, max(r - half, 0f), max(r - half, 0f), rimP)
            return
        }
        val refl = Prefs.reflectionIntensity / 100f
        m.setTranslate(GlassEngine.tiltX * w * 0.25f, GlassEngine.tiltY * h * 0.25f)
        rimS?.setLocalMatrix(m)
        rimP.shader = rimS
        rimP.alpha = (255 * (0.30f + 0.70f * refl)).toInt()
        canvas.drawRoundRect(half, half, w - half, h - half, max(r - half, 0f), max(r - half, 0f), rimP)
        if (refl > 0.02f) {
            val t = GlassEngine.timeSec
            val cx: Float
            val cy: Float
            if (touchAmt > 0.01f) { cx = touchX; cy = touchY } else {
                cx = w * (0.5f + 0.38f * sin(t * 0.35f + phase)) + GlassEngine.tiltX * w * 0.4f
                cy = h * (0.12f + 0.12f * cos(t * 0.27f + phase)) + GlassEngine.tiltY * h * 0.3f
            }
            m.setTranslate(cx, cy)
            sheenS?.setLocalMatrix(m)
            sheenP.shader = sheenS
            sheenP.alpha = min(255f, 255f * (0.06f + 0.16f * refl + 0.3f * touchAmt)).toInt()
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(0f, 0f, w, h, sheenP)
            if (Fx.reflections) {
                val s = ((t + phase * 3f) % 7f) / 7f
                if (s < 0.45f) {
                    val p = s / 0.45f
                    m.setRotate(18f)
                    m.postTranslate((-0.2f + 1.4f * p) * w, h / 2f)
                    bandS?.setLocalMatrix(m)
                    bandP.shader = bandS
                    bandP.alpha = (255 * 0.5f * refl * sin(p * Math.PI.toFloat())).toInt().coerceIn(0, 255)
                    canvas.drawRect(0f, 0f, w, h, bandP)
                }
            }
            canvas.restore()
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (Fx.liquid) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { touchX = e.x; touchY = e.y; setTouchTarget(1f) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setTouchTarget(0f)
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun setTouchTarget(t: Float) {
        if (touchTarget == t) { if (t == 1f) invalidate(); return }
        touchTarget = t
        touchAnim?.cancel()
        touchAnim = ValueAnimator.ofFloat(touchAmt, t).apply {
            duration = if (t > 0f) 120L else 420L
            addUpdateListener { touchAmt = it.animatedValue as Float; invalidate() }
            start()
        }
    }
}

class GlassScrollView(ctx: Context) : ScrollView(ctx) {
    init { isVerticalScrollBarEnabled = false; clipToPadding = false; overScrollMode = OVER_SCROLL_ALWAYS }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); GlassEngine.onScroll() }
}

// ================================================================ Wallpaper
object WallpaperStore {
    var bitmap: Bitmap? = null
        private set
    private var loadedUri: String? = "__none__"

    fun ensureLoaded(c: Context, done: () -> Unit) {
        val u = Prefs.wallpaperUri
        if (u == loadedUri) { done(); return }
        loadedUri = u
        if (u == null) { bitmap = null; done(); return }
        val app = c.applicationContext
        val h = Handler(Looper.getMainLooper())
        Thread {
            var bmp: Bitmap? = null
            try {
                val uri = Uri.parse(u)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1
                while (o.outWidth / s > 1800 || o.outHeight / s > 1800) s *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = s }
                bmp = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            } catch (_: Throwable) {
            }
            h.post { bitmap = bmp; done() }
        }.start()
    }

    fun set(c: Context, uri: Uri, done: () -> Unit) { Prefs.wallpaperUri = uri.toString(); ensureLoaded(c, done) }
    fun clear(c: Context, done: () -> Unit) { Prefs.wallpaperUri = null; ensureLoaded(c, done) }
}

class WallpaperView(ctx: Context) : View(ctx) {
    var forceDark: Boolean? = null
    private class Blob(val x: Float, val y: Float, val r: Float, val cd: Int, val cl: Int, val sp: Float, val ph: Float)
    private val blobs = listOf(
        Blob(0.20f, 0.18f, 0.62f, 0xFF2D5BFF.toInt(), 0xFF7EA6FF.toInt(), 0.23f, 0f),
        Blob(0.85f, 0.32f, 0.55f, 0xFF8B3DFF.toInt(), 0xFFC3A1FF.toInt(), 0.19f, 1.7f),
        Blob(0.30f, 0.62f, 0.60f, 0xFF00B8C4.toInt(), 0xFF8EE5EA.toInt(), 0.17f, 3.1f),
        Blob(0.80f, 0.85f, 0.62f, 0xFFFF3D7F.toInt(), 0xFFFFA9C7.toInt(), 0.21f, 4.4f),
        Blob(0.50f, 0.40f, 0.35f, 0xFFFFA23D.toInt(), 0xFFFFD39A.toInt(), 0.26f, 5.5f)
    )
    private val paints = Array(blobs.size) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private var builtDark: Boolean? = null
    private var builtW = 0
    private var builtH = 0

    override fun onAttachedToWindow() { super.onAttachedToWindow(); GlassEngine.register(this) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); GlassEngine.unregister(this) }

    private fun build(dark: Boolean) {
        builtDark = dark; builtW = width; builtH = height
        val big = max(width, height).toFloat()
        val aMain = if (dark) 0xB0 else 0xC0
        for (i in blobs.indices) {
            val b = blobs[i]
            val c = if (dark) b.cd else b.cl
            val c0 = ColorUtils.setAlphaComponent(c, aMain)
            val c1 = ColorUtils.setAlphaComponent(c, aMain / 3)
            val c2 = ColorUtils.setAlphaComponent(c, 0)
            paints[i].shader = RadialGradient(0f, 0f, big * b.r, intArrayOf(c0, c1, c2), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val dark = forceDark ?: Palette.dark(context)
        c.drawColor(if (dark) 0xFF04060B.toInt() else 0xFFEDEFF5.toInt())
        val t = GlassEngine.timeSec
        val bmp = WallpaperStore.bitmap
        if (bmp != null) {
            val s = max(w / bmp.width, h / bmp.height) * 1.15f
            c.save()
            c.translate(w / 2f + sin(t * 0.05f) * w * 0.03f, h / 2f + cos(t * 0.04f) * h * 0.02f)
            c.scale(s, s)
            c.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, null)
            c.restore()
            c.drawColor(if (dark) 0x44000000 else 0x22FFFFFF)
            return
        }
        if (builtDark != dark || builtW != width || builtH != height) build(dark)
        for (i in blobs.indices) {
            val b = blobs[i]
            val cx = w * (b.x + 0.12f * sin(t * b.sp + b.ph))
            val cy = h * (b.y + 0.10f * cos(t * b.sp * 0.8f + b.ph))
            c.save()
            c.translate(cx, cy)
            c.drawCircle(0f, 0f, max(w, h) * b.r, paints[i])
            c.restore()
        }
    }
}

// ================================================================ Controls
class IOSSwitch(ctx: Context) : View(ctx) {
    var checked = false
        private set
    var listener: ((Boolean) -> Unit)? = null
    private var p = 0f
    private var press = 0f
    private var anim: ValueAnimator? = null
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val kp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x22000000 }

    init { setOnClickListener { setChecked(!checked, true); Haptics.confirm(this); listener?.invoke(checked) } }

    fun setChecked(v: Boolean, animate: Boolean) {
        checked = v
        anim?.cancel()
        val target = if (v) 1f else 0f
        if (!animate || !Fx.transitions) { p = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(p, target).apply {
            duration = Fx.dur(320L)
            if (Fx.spring) interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(wm: Int, hm: Int) { setMeasuredDimension(context.dpi(51f), context.dpi(31f)) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { press = 1f; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { press = 0f; invalidate() }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val off = if (Palette.dark(context)) 0x66787880 else 0x40787880
        tp.color = ColorUtils.blendARGB(off, Palette.green, p.coerceIn(0f, 1f))
        c.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, tp)
        val m = context.dp(2f)
        val kh = h - 2 * m
        val kw = context.dp(27f) + press * context.dp(4f)
        val left = m + (w - 2 * m - kw) * p
        rp.strokeWidth = context.dp(0.6f)
        c.drawRoundRect(left, m, left + kw, m + kh, kh / 2f, kh / 2f, kp)
        c.drawRoundRect(left, m, left + kw, m + kh, kh / 2f, kh / 2f, rp)
    }
}

class GlassSlider(ctx: Context, private val mode: Mode) : View(ctx) {
    enum class Mode { PILL, THIN }

    private var icon: Drawable? = null
    var iconRes = 0
        set(v) { field = v; icon = if (v != 0) ContextCompat.getDrawable(context, v)?.mutate() else null; invalidate() }
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onChange: ((Float, Boolean) -> Unit)? = null
    var canStart: (() -> Boolean)? = null
    var isDragging = false
        private set
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var lastStep = -1

    override fun onMeasure(wm: Int, hm: Int) {
        if (mode == Mode.THIN) setMeasuredDimension(resolveSize(context.dpi(200f), wm), context.dpi(36f))
        else setMeasuredDimension(resolveSize(context.dpi(70f), wm), resolveSize(context.dpi(150f), hm))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (canStart?.invoke() == false) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                update(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> { if (isDragging) update(e); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { isDragging = false; return true }
        }
        return super.onTouchEvent(e)
    }

    private fun pad() = context.dp(14f)

    private fun update(e: MotionEvent) {
        value = if (mode == Mode.PILL) 1f - e.y / height else (e.x - pad()) / (width - 2 * pad())
        onChange?.invoke(value, true)
        val step = (value * 10).toInt()
        if (step != lastStep) { lastStep = step; Haptics.tick(this) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dark = Palette.dark(context)
        if (mode == Mode.PILL) {
            val r = min(context.dp(26f), w / 2f)
            path.reset()
            path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(path)
            tp.color = if (dark) 0x33FFFFFF else 0x22000000
            c.drawRect(0f, 0f, w, h, tp)
            fp.color = if (dark) 0xF2FFFFFF.toInt() else 0xE61C1C1E.toInt()
            c.drawRect(0f, h - h * value, w, h, fp)
            c.restore()
            icon?.let {
                val s = context.dp(22f).toInt()
                val cx = (w / 2f).toInt()
                val cy = (h - context.dp(30f)).toInt()
                it.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
                val covered = value * h > context.dp(30f)
                val ink = if (covered) (if (dark) 0xFF1C1C1E.toInt() else Color.WHITE) else Palette.text(context)
                it.setColorFilter(ink, PorterDuff.Mode.SRC_IN)
                it.draw(c)
            }
        } else {
            val p = pad()
            val th = context.dp(4f)
            val cy = h / 2f
            tp.color = if (dark) 0x40FFFFFF else 0x30000000
            c.drawRoundRect(p, cy - th / 2, w - p, cy + th / 2, th / 2, th / 2, tp)
            val x = p + (w - 2 * p) * value
            fp.color = Palette.accent
            c.drawRoundRect(p, cy - th / 2, x, cy + th / 2, th / 2, th / 2, fp)
            fp.color = Color.WHITE
            val kr = context.dp(11f) + (if (isDragging) context.dp(2f) else 0f)
            c.drawCircle(x, cy, kr, fp)
            tp.color = 0x33000000
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = context.dp(0.7f)
            c.drawCircle(x, cy, kr, tp)
            tp.style = Paint.Style.FILL
        }
    }
}

class Segmented(ctx: Context, private val items: List<String>, selected: Int, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
    private val labels = ArrayList<TextView>()
    var index = selected
        private set

    init {
        orientation = HORIZONTAL
        val dark = Palette.dark(ctx)
        background = Ui.round(if (dark) 0x26FFFFFF else 0x14000000, ctx.dp(12f))
        val pad = ctx.dpi(3f)
        setPadding(pad, pad, pad, pad)
        items.forEachIndexed { i, s ->
            val t = Ui.tv(ctx, s, 13f, Palette.text(ctx), Typeface.NORMAL, "sans-serif-medium")
            t.gravity = Gravity.CENTER
            t.setOnClickListener { Haptics.tick(it); select(i); onSelect(i) }
            addView(t, llp(0, ctx.dpi(34f), 1f))
            labels.add(t)
        }
        paint()
    }

    private fun select(i: Int) { index = i; paint() }

    private fun paint() {
        val dark = Palette.dark(context)
        for (i in labels.indices) {
            labels[i].background = if (i == index) Ui.round(if (dark) 0x59FFFFFF else 0xFFFFFFFF.toInt(), context.dp(10f)) else null
        }
    }
}

// ================================================================ Builders
object Blocks {
    fun card(c: Context, backdrop: View?, cornerDp: Float = 26f, padDp: Float = 16f): GlassView =
        GlassView(c).also {
            it.backdrop = backdrop
            it.corner = c.dp(cornerDp)
            val p = c.dpi(padDp)
            it.setPadding(p, p, p, p)
        }

    fun section(c: Context, s: String): TextView =
        Ui.tv(c, s.uppercase(L.locale()), 12.5f, Palette.text2(c), Typeface.NORMAL, "sans-serif-medium").apply { letterSpacing = 0.06f }

    fun button(c: Context, backdrop: View?, label: String, accent: Int = 0, iconRes: Int = 0, onClick: () -> Unit): GlassView {
        val b = GlassView(c)
        b.backdrop = backdrop
        b.corner = c.dp(26f)
        b.tint = accent
        val ink = if (accent != 0) Color.WHITE else Palette.text(c)
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (iconRes != 0) row.addView(Ui.icon(c, iconRes, 20f, ink), llp(c.dpi(20f), c.dpi(20f), r = c.dpi(8f)))
        row.addView(Ui.tv(c, label, 15f, ink, Typeface.NORMAL, "sans-serif-medium"))
        b.addView(row, flp(WRAP, WRAP, Gravity.CENTER))
        b.setOnClickListener { Haptics.tick(it); onClick() }
        Motion.elastic(b)
        return b
    }

    fun enabledLook(v: View, on: Boolean) { v.isEnabled = on; v.isClickable = on; v.alpha = if (on) 1f else 0.4f }

    fun switchRow(c: Context, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dpi(10f), 0, c.dpi(10f))
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.tv(c, title, 16f, Palette.text(c), Typeface.NORMAL, "sans-serif-medium"))
        col.addView(Ui.tv(c, sub, 12.5f, Palette.text2(c)), llp(WRAP, WRAP, t = c.dpi(2f)))
        row.addView(col, llp(0, WRAP, 1f, r = c.dpi(12f)))
        val sw = IOSSwitch(c)
        sw.setChecked(checked, false)
        sw.listener = onChange
        row.addView(sw, llp(WRAP, WRAP))
        return row
    }

    fun divider(c: Context): View = View(c).apply {
        setBackgroundColor(if (Palette.dark(c)) 0x1FFFFFFF else 0x14000000)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1)
    }
}
