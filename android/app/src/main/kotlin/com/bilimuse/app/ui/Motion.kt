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
    const val Fast = 120
    const val Base = 180
    const val Slow = 240
    const val Cinematic = 620
    const val DialogExit = 120
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
): Modifier = this

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
    shape: Shape = RoundedCornerShape(0.dp),
): Modifier {
    val motion = LocalMotion.current
    val base = BiliPanel2
    val sweepColor = BiliPanel
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
            color = sweepColor,
            topLeft = Offset(x - band, 0f),
            size = androidx.compose.ui.geometry.Size(band * 2f, size.height),
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
                    initialScale = 0.98f,
                    animationSpec = motion.tween(MotionTokens.Base, easing = MotionEase.OutQuart),
                ),
            exit = fadeOut(motion.tween(MotionTokens.Fast, easing = MotionEase.In)) +
                scaleOut(
                    targetScale = 0.98f,
                    animationSpec = motion.tween(MotionTokens.DialogExit, easing = MotionEase.In),
                ),
        ) {
            content()
        }
    }
}
