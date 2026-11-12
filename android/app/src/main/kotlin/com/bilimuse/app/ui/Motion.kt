package com.bilimuse.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 安卓端的动效令牌。
 *
 * 设计原则和网页 / 桌面版保持一致：只动 transform 与 opacity，不用会触发重排的
 * 布局动画；页面转场用 expo-out，按下反馈用短 spring，装饰性的光灯动效放慢到
 * 8–12 秒，避免和内容抢注意力。
 */
object MotionTokens {
    const val Fast = 140
    const val Base = 240
    const val Slow = 420
    const val Cinematic = 620
    const val DialogExit = 180
    const val StaggerStep = 28
    const val StaggerCap = 16
}

object MotionEase {
    val OutExpo: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val OutQuart: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val InOut: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val In: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val Springy: Easing = CubicBezierEasing(0.34f, 1.46f, 0.64f, 1f)
}

/**
 * 动效开关的实际取值。
 *
 * 用户在设置里关掉动效，或者系统「移除动画 / 减少动态效果」打开时，[enabled]
 * 为 false。所有新增动效都应该走这里，而不是直接写死 tween。
 */
@Immutable
class MotionSpec(val enabled: Boolean) {
    fun <T> tween(
        durationMs: Int,
        delayMs: Int = 0,
        easing: Easing = MotionEase.OutExpo,
    ): FiniteAnimationSpec<T> = if (!enabled) {
        snap()
    } else {
        TweenSpec(durationMillis = durationMs, delay = delayMs, easing = easing)
    }

    fun <T> spring(
        dampingRatio: Float = 0.8f,
        stiffness: Float = 380f,
    ): FiniteAnimationSpec<T> = if (!enabled) {
        snap()
    } else {
        SpringSpec(dampingRatio = dampingRatio, stiffness = stiffness)
    }

    fun staggerDelay(index: Int): Int = if (!enabled) {
        0
    } else {
        index.coerceIn(0, MotionTokens.StaggerCap) * MotionTokens.StaggerStep
    }
}

val LocalMotion = staticCompositionLocalOf { MotionSpec(true) }

/**
 * 把系统动画设置和用户设置合成一个 [LocalMotion]。
 *
 * `ValueAnimator.areAnimatorsEnabled()` 在 Android 的「移除动画」开关下会返回 false；
 * 再直接读一次 Settings.Global，覆盖部分 OEM 只改过渡缩放、不改 animator 的情况。
 */
@Composable
fun MotionProvider(userEnabled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var systemEnabled by remember { mutableStateOf(systemAnimationsEnabled(context)) }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemEnabled = systemAnimationsEnabled(context)
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
            false,
            observer,
        )
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    val enabled = userEnabled && systemEnabled
    val spec = remember(enabled) { MotionSpec(enabled) }
    CompositionLocalProvider(LocalMotion provides spec, content = content)
}

private fun systemAnimationsEnabled(context: Context): Boolean {
    if (!ValueAnimator.areAnimatorsEnabled()) return false
    val resolver = context.contentResolver
    val animator = Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    val transition = Settings.Global.getFloat(
        resolver,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        1f,
    )
    return animator > 0f && transition > 0f
}

/**
 * 列表 / 版面的错峰入场：透明度 + 位移 + 极轻的缩放。
 *
 * 默认的 reference key 是 Unit，因此每个 item 第一次进入组合时播放一次；延迟按
 * 下标封顶，长列表滚到后面也不会出现越来越夸张的等待。
 */
@Composable
fun Modifier.staggerIn(
    index: Int,
    distance: Dp = 12.dp,
    scaleFrom: Float = 1f,
    key: Any? = Unit,
): Modifier {
    val motion = LocalMotion.current
    if (!motion.enabled) return this

    var entered by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { entered = true }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = motion.tween(
            durationMs = MotionTokens.Slow,
            delayMs = motion.staggerDelay(index),
            easing = MotionEase.OutExpo,
        ),
        label = "stagger-in",
    )

    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * distance.toPx()
        if (scaleFrom < 1f) {
            val scale = scaleFrom + (1f - scaleFrom) * progress
            scaleX = scale
            scaleY = scale
        }
    }
}

/** 按下时轻微收紧：短 spring，松手立刻回弹。 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.94f,
): Modifier {
    val motion = LocalMotion.current
    if (!motion.enabled) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = motion.spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "press-scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 骨架屏流光。状态只在绘制阶段读取，每帧只重绘、不重组。
 */
@Composable
fun Modifier.skeleton(
    shape: Shape = RoundedCornerShape(4.dp),
    base: Color = BiliPanel2,
): Modifier {
    val motion = LocalMotion.current
    val clipped = clip(shape)
    if (!motion.enabled) return clipped.background(base)

    val transition = rememberInfiniteTransition(label = "skeleton")
    val sweep by transition.animateFloat(
        initialValue = -0.8f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 1400,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-sweep",
    )
    return clipped.drawBehind {
        drawRect(base)
        val x = sweep * size.width
        val band = size.width.coerceAtLeast(1f) * 0.55f
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    BiliPanel.copy(alpha = 0.85f),
                    Color.Transparent,
                ),
                start = Offset(x - band, 0f),
                end = Offset(x + band, size.height),
            ),
        )
    }
}

// ---------------------------------------------------------------- 封面视差

@Stable
class CoverTiltState {
    var rotationX by mutableFloatStateOf(0f)
        internal set
    var rotationY by mutableFloatStateOf(0f)
        internal set
    var highlightX by mutableFloatStateOf(0.5f)
        internal set
    var highlightY by mutableFloatStateOf(0.32f)
        internal set

    internal fun reset() {
        rotationX = 0f
        rotationY = 0f
        highlightX = 0.5f
        highlightY = 0.32f
    }
}

/**
 * 用重力传感器做封面的轻微视差。
 *
 * 以打开播放页时的握持姿态为零点，基线缓慢跟随当前姿态，所以转动手机时会先
 * 倾斜、随后自然回正；平放在桌上也能得到同样的效果。传感器不可用或关闭动效
 * 时完全不动。
 */
@Composable
fun rememberCoverTilt(enabled: Boolean): CoverTiltState {
    val state = remember { CoverTiltState() }
    val motion = LocalMotion.current
    val context = LocalContext.current

    DisposableEffect(enabled, motion.enabled) {
        if (!enabled || !motion.enabled) {
            state.reset()
            return@DisposableEffect onDispose { }
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            state.reset()
            return@DisposableEffect onDispose { }
        }

        var baselineX: Float? = null
        var baselineY: Float? = null
        var smoothX = 0f
        var smoothY = 0f
        var lastEventAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val now = SystemClock.uptimeMillis()
                if (now - lastEventAt < 32L) return
                lastEventAt = now

                val x = -event.values[0] / SensorManager.GRAVITY_EARTH
                val y = -event.values[1] / SensorManager.GRAVITY_EARTH
                val baseX = baselineX ?: x.also { baselineX = it }
                val baseY = baselineY ?: y.also { baselineY = it }
                // 基线缓慢跟随姿态：倾斜后封面会回正，而不是卡在最大角度。
                baselineX = baseX + (x - baseX) * 0.012f
                baselineY = baseY + (y - baseY) * 0.012f

                val dx = (x - baselineX!!).coerceIn(-1f, 1f)
                val dy = (y - baselineY!!).coerceIn(-1f, 1f)
                smoothX = smoothX * 0.82f + dx * 0.18f
                smoothY = smoothY * 0.82f + dy * 0.18f

                state.rotationY = (smoothX * 7f).coerceIn(-7f, 7f)
                state.rotationX = (-smoothY * 7f).coerceIn(-7f, 7f)
                state.highlightX = 0.5f + smoothX.coerceIn(-1f, 1f) * 0.34f
                state.highlightY = 0.32f + smoothY.coerceIn(-1f, 1f) * 0.24f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { runCatching { manager.unregisterListener(listener) } }
    }

    return state
}

/** 把倾斜量应用到 3D 图层；读取状态只触发图层更新，不触发整页重组。 */
fun Modifier.coverTilt(tilt: CoverTiltState): Modifier = graphicsLayer {
    rotationX = tilt.rotationX
    rotationY = tilt.rotationY
    cameraDistance = 16f * density
}

/** 随倾斜移动的高光与环境光；在绘制阶段读取状态，开销只有一层。 */
fun Modifier.coverGlare(tilt: CoverTiltState, accent: Color): Modifier = drawBehind {
    val center = Offset(
        x = size.width * tilt.highlightX,
        y = size.height * tilt.highlightY,
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.05f),
                Color.Transparent,
            ),
            center = center,
            radius = size.maxDimension * 0.9f,
        ),
    )
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                accent.copy(alpha = 0.12f),
                Color.Transparent,
                Color.Transparent,
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
}

// ---------------------------------------------------------------- 封面主色

/** 封面的强调色，外加一个给 AsyncImage 回调用的钩子。 */
class CoverAccent(
    val color: Color,
    val onCoverLoaded: (Drawable?) -> Unit,
)

private val coverAccentCache = LruCache<String, Int>(12)

private fun cachedCoverAccent(cover: String): Color? =
    coverAccentCache.get(cover)?.let { Color(it) }

private fun cacheCoverAccent(cover: String, color: Color) {
    if (cover.isNotBlank()) coverAccentCache.put(cover, color.toArgb())
}

/**
 * 从封面缩略图里取一个高饱和主色，作为播放页背景光晕和播放键的颜色。
 *
 * 不引入 Palette 依赖：把图缩到 32×32，按色相分桶，取权重最高的桶及左右邻居
 * 求加权平均，再把饱和度 / 明度夹到适合深色 UI 的范围。灰度封面回退到粉色。
 */
@Composable
fun rememberCoverAccent(cover: String, fallback: Color = BiliPink): CoverAccent {
    val motion = LocalMotion.current
    val cached = remember(cover) { cachedCoverAccent(cover) }
    var target by remember(cover) { mutableStateOf(cached ?: fallback) }
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = motion.tween(durationMs = 720, easing = MotionEase.OutExpo),
        label = "cover-accent",
    )
    val onLoaded: (Drawable?) -> Unit = { drawable ->
        val extracted = extractCoverAccent(drawable)
        if (extracted != null && extracted != target) {
            target = extracted
            cacheCoverAccent(cover, extracted)
        }
    }
    return CoverAccent(animated, onLoaded)
}

fun extractCoverAccent(drawable: Drawable?): Color? {
    if (drawable == null) return null
    return runCatching {
        val bitmap = drawable.toBitmap(
            width = 32,
            height = 32,
            config = Bitmap.Config.ARGB_8888,
        )
        if (bitmap.width <= 0 || bitmap.height <= 0) return@runCatching null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val bins = 24
        val weights = FloatArray(bins)
        val reds = FloatArray(bins)
        val greens = FloatArray(bins)
        val blues = FloatArray(bins)
        for (pixel in pixels) {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 160) continue
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val maxChannel = max(r, max(g, b))
            val minChannel = min(r, min(g, b))
            if (maxChannel < 24) continue
            val delta = maxChannel - minChannel
            if (delta == 0) continue
            val saturation = delta / maxChannel.toFloat()
            val value = maxChannel / 255f
            if (saturation < 0.14f || value < 0.16f) continue

            val hue = when (maxChannel) {
                r -> 60f * (((g - b) / delta.toFloat()) % 6f)
                g -> 60f * ((b - r) / delta.toFloat() + 2f)
                else -> 60f * ((r - g) / delta.toFloat() + 4f)
            }.let { if (it < 0f) it + 360f else it }
            val bin = ((hue / 360f) * bins).toInt().coerceIn(0, bins - 1)
            val weight = saturation * (0.35f + value * 0.65f)
            weights[bin] += weight
            reds[bin] += r * weight
            greens[bin] += g * weight
            blues[bin] += b * weight
        }

        var bestBin = -1
        var bestWeight = 0f
        for (index in 0 until bins) {
            if (weights[index] > bestWeight) {
                bestWeight = weights[index]
                bestBin = index
            }
        }
        if (bestBin < 0 || bestWeight <= 0f) return@runCatching null

        var sumWeight = 0f
        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        for (offset in -1..1) {
            val index = Math.floorMod(bestBin + offset, bins)
            val weight = weights[index]
            if (weight <= 0f) continue
            sumWeight += weight
            sumR += reds[index]
            sumG += greens[index]
            sumB += blues[index]
        }
        if (sumWeight <= 0f) return@runCatching null

        val hsl = FloatArray(3)
        ColorUtils.RGBToHSL(
            (sumR / sumWeight).roundToInt().coerceIn(0, 255),
            (sumG / sumWeight).roundToInt().coerceIn(0, 255),
            (sumB / sumWeight).roundToInt().coerceIn(0, 255),
            hsl,
        )
        hsl[1] = hsl[1].coerceIn(0.42f, 0.8f)
        hsl[2] = hsl[2].coerceIn(0.5f, 0.72f)
        Color(ColorUtils.HSLToColor(hsl))
    }.getOrNull()
}

// ---------------------------------------------------------------- 环境光与弹窗

/**
 * 播放页背景的两团缓慢漂移的光晕，颜色来自封面主色。
 * 只改变图层位移与缩放，不在每帧重建渐变。
 */
@Composable
fun AuroraGlow(accent: Color, modifier: Modifier = Modifier) {
    val motion = LocalMotion.current
    val drift by if (motion.enabled) {
        val transition = rememberInfiniteTransition(label = "aurora")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    durationMillis = 11_000,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "aurora-drift",
        )
    } else {
        remember { mutableFloatStateOf(0.5f) }
    }

    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-190).dp)
                .size(540.dp)
                .graphicsLayer {
                    translationX = (drift - 0.5f) * 96.dp.toPx()
                    translationY = (drift - 0.5f) * 18.dp.toPx()
                    scaleX = 1.02f + drift * 0.08f
                    scaleY = 1.04f + drift * 0.06f
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.22f),
                            accent.copy(alpha = 0.07f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 120.dp, y = 140.dp)
                .size(420.dp)
                .graphicsLayer {
                    translationY = (0.5f - drift) * 70.dp.toPx()
                    scaleX = 1.08f - drift * 0.05f
                    scaleY = 1.08f - drift * 0.05f
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            BiliBlue.copy(alpha = 0.12f),
                            accent.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

/**
 * 带缩放 / 淡入淡出的 [Dialog]。
 *
 * 退出时先让内容缩回去，再让父层移除 Dialog；系统返回键和点外部也会走同一条
 * 关闭路径，不会出现「弹窗已经没了、动画还在跑」的割裂。
 */
@Composable
fun MotionDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val motion = LocalMotion.current
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(closing) {
        if (closing) {
            if (motion.enabled) delay(MotionTokens.DialogExit.toLong())
            onDismissRequest()
        }
    }

    val requestClose: () -> Unit = {
        if (!closing) {
            closing = true
            visible = false
        }
    }

    Dialog(onDismissRequest = requestClose, properties = properties) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motion.tween(MotionTokens.Base)) +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec = motion.spring(dampingRatio = 0.78f, stiffness = 420f),
                ),
            exit = fadeOut(motion.tween(MotionTokens.Fast, easing = MotionEase.In)) +
                scaleOut(
                    targetScale = 0.96f,
                    animationSpec = motion.tween(MotionTokens.DialogExit, easing = MotionEase.In),
                ),
        ) {
            content()
        }
    }
}
