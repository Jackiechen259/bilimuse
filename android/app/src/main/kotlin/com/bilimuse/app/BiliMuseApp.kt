package com.bilimuse.app

import android.app.Application
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

class BiliMuseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
