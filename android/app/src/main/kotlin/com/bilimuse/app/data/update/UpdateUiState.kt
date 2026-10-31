package com.bilimuse.app.data.update

import java.io.File

enum class UpdateStatus {
    IDLE,
    CHECKING,
    NO_RELEASE,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    FAILED,
}

/** 设置页与更新弹窗共享的状态。 */
data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val update: AppUpdate? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val apkFile: File? = null,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else {
            0f
        }

    /** 有可查看/可安装的更新时，设置页的按钮打开弹窗而不是重新检查。 */
    val hasOpenableUpdate: Boolean
        get() = update != null && when (status) {
            UpdateStatus.AVAILABLE,
            UpdateStatus.DOWNLOADING,
            UpdateStatus.READY,
            UpdateStatus.FAILED,
            -> true
            else -> false
        }
}
