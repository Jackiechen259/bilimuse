package com.bilimuse.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bilimuse.app.MainViewModel
import com.bilimuse.app.data.WebCookieStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

private const val LOGIN_URL =
    "https://passport.bilibili.com/h5-app/passport/login" +
        "?gourl=https%3A%2F%2Fwww.bilibili.com%2F&lang=zh-CN"

/**
 * 密码 / 手机验证码登录。
 *
 * B 站这两种登录都必须先过极验人机验证，自己实现验证码 SDK 既脆弱又容易踩风控。
 * 所以这里加载 B 站官方 H5 登录页（账号密码、短信验证码、人机验证都在官方页面里），
 * 成功后只把 .bilibili.com 的 cookie 换进原生会话；密码永远不进入 App。
 */
@Composable
fun WebLoginScreen(
    viewModel: MainViewModel,
    initialPassword: Boolean,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    var pageReady by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var retrySignal by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = !saving) { onDismiss() }

    // 每次打开都从干净的 WebView cookie 开始，避免把旧账号带进官方登录页
    LaunchedEffect(Unit) {
        val done = CompletableDeferred<Unit>()
        WebCookieStore.clear { done.complete(Unit) }
        withTimeoutOrNull(2_500) { done.await() }
        pageReady = true
    }

    // 官方页登录成功后会写入 cookie；轮询到 SESSDATA 就换进原生会话
    LaunchedEffect(pageReady, retrySignal) {
        if (!pageReady || attempted) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            val cookies = WebCookieStore.read()
            if (!WebCookieStore.hasLoginCookies(cookies)) continue
            attempted = true
            saving = true
            error = null
            val result = viewModel.completeWebLogin(cookies)
            saving = false
            if (result.isSuccess) {
                onLoggedIn()
                return@LaunchedEffect
            }
            error = "登录态校验失败：${result.exceptionOrNull()?.message ?: "请重试"}"
            break
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.let {
                it.stopLoading()
                it.destroy()
            }
            webViewHolder[0] = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(AppBackgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, enabled = !saving) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = BiliText,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (initialPassword) "账号密码登录" else "手机验证码登录",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "B 站官方登录页 · 含人机验证",
                        style = MaterialTheme.typography.bodySmall,
                        color = BiliMuted,
                    )
                }
                if (pageReady) {
                    IconButton(onClick = { webViewHolder[0]?.reload() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新登录页",
                            tint = BiliMuted,
                        )
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                if (!pageReady) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BiliPink, strokeWidth = 2.5.dp)
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .border(1.dp, BiliLine, RoundedCornerShape(16.dp)),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                createLoginWebView(
                                    context = context,
                                    initialPassword = initialPassword,
                                    onLoadingChanged = { pageLoading = it },
                                    onError = { error = it },
                                ).also { webView ->
                                    webViewHolder[0] = webView
                                }
                            },
                        )
                    }
                }
                if (pageLoading && pageReady) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter),
                        color = BiliPink,
                        trackColor = Color.Transparent,
                    )
                }
                if (saving) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xD9090B10)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            color = BiliPanel,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, BiliLine),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = BiliPink,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.size(12.dp))
                                Text("正在验证登录…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            error?.let { message ->
                Surface(
                    color = BiliDanger.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, BiliDanger.copy(alpha = 0.25f)),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = BiliDanger,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                error = null
                                attempted = false
                                retrySignal += 1
                                if (!WebCookieStore.hasLoginCookies(WebCookieStore.read())) {
                                    webViewHolder[0]?.reload()
                                }
                            },
                        ) {
                            Text("重试", color = BiliPink)
                        }
                    }
                }
            }

            Text(
                text = "登录在 B 站官方页面完成，App 只读取登录 cookie，不会保存密码。" +
                    "遇到滑动/点选验证请按页面提示操作。",
                style = MaterialTheme.typography.bodySmall,
                color = BiliMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createLoginWebView(
    context: Context,
    initialPassword: Boolean,
    onLoadingChanged: (Boolean) -> Unit,
    onError: (String?) -> Unit,
): WebView = WebView(context).apply {
    var switchedToPassword = false
    setBackgroundColor(android.graphics.Color.WHITE)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.saveFormData = false
    // App 是深色主题，但 B 站登录页按自己的浅色设计渲染最稳，避免系统算法反色
    if (Build.VERSION.SDK_INT >= 33) {
        settings.setAlgorithmicDarkeningAllowed(false)
    } else if (Build.VERSION.SDK_INT >= 29) {
        @Suppress("DEPRECATION")
        settings.forceDark = WebSettings.FORCE_DARK_OFF
    }
    // 带 "wv" 的默认 UA 容易被登录风控拦截，换成普通手机 Chrome UA
    settings.userAgentString =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onLoadingChanged(true)
            onError(null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onLoadingChanged(false)
            CookieManager.getInstance().flush()
            // 官方页默认落在短信表单；密码入口按钮是纯前端切换，这里帮用户点一下。
            // 选择器失效也不影响手动点击“账号密码登录”。
            if (initialPassword && !switchedToPassword) {
                switchedToPassword = true
                fun trySwitchToPassword(remaining: Int) {
                    val webView = view ?: return
                    if (!webView.isAttachedToWindow) return
                    runCatching {
                        webView.evaluateJavascript(
                            "(function(){var e=document.querySelector('.login-type-toggle');" +
                                "if(e){e.click();return 'ok';}return 'missing';})()",
                        ) { result ->
                            // Vue 渲染有延迟；只有在还没找到入口时才重试，避免点两次又切回短信
                            if (result?.contains("missing") == true && remaining > 0 &&
                                webView.isAttachedToWindow
                            ) {
                                webView.postDelayed({ trySwitchToPassword(remaining - 1) }, 700L)
                            }
                        }
                    }
                }
                view?.postDelayed({ trySwitchToPassword(3) }, 500L)
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val target = request?.url ?: return false
            if (target.scheme == "https" && isTrustedBiliHost(target.host)) return false
            onError("已拦截非 B 站页面：${target.host ?: target.scheme ?: "未知地址"}")
            return true
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val target = url?.let { Uri.parse(it) }
            val host = target?.host
            if (target?.scheme == "https" && isTrustedBiliHost(host)) return false
            onError("已拦截非 B 站页面：${host ?: "未知地址"}")
            return true
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                onLoadingChanged(false)
                onError("登录页加载失败：${error?.description ?: "网络错误"}")
            }
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            handler?.cancel()
            onLoadingChanged(false)
            onError("登录页 HTTPS 证书校验失败，已阻止连接")
        }
    }
    loadUrl(LOGIN_URL, mapOf("Accept-Language" to "zh-CN,zh;q=0.9"))
}

private fun isTrustedBiliHost(host: String?): Boolean {
    val value = host?.lowercase() ?: return false
    return value == "bilibili.com" || value.endsWith(".bilibili.com") ||
        value == "biligame.com" || value.endsWith(".biligame.com") ||
        value == "hdslb.com" || value.endsWith(".hdslb.com") ||
        value == "geetest.com" || value.endsWith(".geetest.com") ||
        value == "geetest.cn" || value.endsWith(".geetest.cn")
}
