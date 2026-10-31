package com.bilimuse.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bilimuse.app.MainViewModel
import com.bilimuse.app.data.QrInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.EnumMap

@Composable
fun LoginDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
    onDemo: () -> Unit,
    onWebLogin: (password: Boolean) -> Unit,
) {
    var qr by remember { mutableStateOf<QrInfo?>(null) }
    var status by remember { mutableStateOf("正在生成二维码…") }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var expired by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        error = false
        expired = false
        qr = null
        status = "正在生成二维码…"
        val result = viewModel.generateQr()
        val info = result.getOrNull()
        if (info == null) {
            error = true
            status = "二维码生成失败：${result.exceptionOrNull()?.message.orEmpty()}"
        } else {
            qr = info
            status = "请用 B 站手机客户端扫码"
        }
        loading = false
    }

    LaunchedEffect(qr?.key) {
        val key = qr?.key ?: return@LaunchedEffect
        while (isActive) {
            delay(2_000)
            val result = viewModel.pollQr(key)
            val poll = result.getOrNull()
            if (poll == null) {
                error = true
                status = "登录失败：${result.exceptionOrNull()?.message.orEmpty()}"
                return@LaunchedEffect
            }
            status = poll.message
            when (poll.status) {
                "success" -> {
                    onLoggedIn()
                    return@LaunchedEffect
                }
                "expired" -> {
                    expired = true
                    return@LaunchedEffect
                }
            }
        }
    }

    val qrBitmap by produceState<Bitmap?>(initialValue = null, qr?.url) {
        val url = qr?.url
        value = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching { makeQrBitmap(url, 760) }.getOrNull()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp),
            color = BiliPanel,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, BiliLine),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "登录 B 站",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = BiliMuted,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(232.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        qrBitmap != null -> Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "登录二维码",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                        )
                        loading -> CircularProgressIndicator(color = BiliPink)
                        else -> Text("二维码不可用", color = Color(0xFF333333))
                    }
                    if (expired) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xCC0B0D12)),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = { attempt += 1 }) {
                                Text("二维码已过期 · 点击刷新", color = BiliPink)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        error || expired -> BiliDanger
                        status.contains("已扫码") -> BiliBlue
                        else -> BiliText
                    },
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "登录态只保存在本机应用私有目录，不碰密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                    textAlign = TextAlign.Center,
                )

                if (error || expired) {
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { attempt += 1 },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BiliPink,
                            contentColor = Color(0xFF1A0A12),
                        ),
                    ) { Text("刷新二维码") }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onWebLogin(true) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BiliLine),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    ) {
                        Text("账号密码", color = BiliText, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { onWebLogin(false) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BiliLine),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    ) {
                        Text("短信验证码", color = BiliText, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "使用 B 站官方登录页，含人机验证",
                    style = MaterialTheme.typography.bodySmall,
                    color = BiliMuted,
                )

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDemo) {
                    Text("先试试演示模式（无需登录）", color = BiliMuted)
                }
            }
        }
    }
}

private fun makeQrBitmap(content: String, size: Int): Bitmap {
    val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
    hints[EncodeHintType.MARGIN] = 1
    hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
    hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    val black = 0xFF0B0D12.toInt()
    val white = 0xFFFFFFFF.toInt()
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix[x, y]) black else white
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
