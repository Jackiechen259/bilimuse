package com.bilimuse.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.bilimuse.app.data.BiliClient
import com.bilimuse.app.data.DemoCdn
import com.bilimuse.app.data.DemoCdnInterceptor
import com.bilimuse.app.data.SessionStore
import com.bilimuse.app.data.SettingsStore
import com.bilimuse.app.data.StreamRegistry
import com.bilimuse.app.data.update.GitHubUpdater
import java.io.File

/** 进程内单例：网络客户端、登录态、已解析音轨缓存。 */
class AppContainer(context: Application) {
    val sessionStore = SessionStore(context)
    val settingsStore = SettingsStore(context)
    val bili = BiliClient(sessionStore)
    val updater = GitHubUpdater(context)

    val demoCdn = DemoCdn(File(context.cacheDir, "bilimuse"))

    val mediaClient = StreamRegistry.createMediaClient(DemoCdnInterceptor(demoCdn))

    val registry = StreamRegistry(
        bili = bili,
        demoCdn = demoCdn,
        mediaClient = mediaClient,
        settingsProvider = { settingsStore.settings.value },
    )
}

class BiliMuseApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * 全局图片交叉淡入：封面、头像等远程图片切换时不再硬切。
     * 播放页 / 迷你条自己还会做一层「旧封面淡出、新封面浮入」的叠化。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(220)
        .build()
}
