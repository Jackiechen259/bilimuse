package com.bilimuse.app.data

import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * 读取 WebView CookieManager 里的 B 站登录态。
 *
 * WebView 与原生 HttpClient 的 cookie 是两套存储：官方登录页成功后，
 * 这里把 .bilibili.com 下的 cookie 取出来交给 BiliClient。
 */
object WebCookieStore {
    private val urls = listOf(
        "https://passport.bilibili.com",
        "https://www.bilibili.com",
        "https://api.bilibili.com",
        "https://space.bilibili.com",
        "https://account.bilibili.com",
    )

    fun read(): Map<String, String> {
        val manager = CookieManager.getInstance()
        manager.flush()
        val merged = LinkedHashMap<String, String>()
        for (url in urls) {
            merged.putAll(parseCookieHeader(manager.getCookie(url)))
        }
        return merged
    }

    fun clear(onDone: () -> Unit) {
        val manager = CookieManager.getInstance()
        // 让官方登录页每次都从“手机号登录”入口开始，也避免记住上一个账号
        runCatching { WebStorage.getInstance().deleteAllData() }
        manager.removeAllCookies { _ -> onDone() }
        manager.flush()
    }

    fun hasLoginCookies(cookies: Map<String, String>): Boolean =
        !cookies["SESSDATA"].isNullOrBlank()
}
