package com.bilimuse.app.data.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 调起系统 APK 安装器，以及处理“安装未知应用”权限。 */
object ApkInstaller {
    private const val MIME_APK = "application/vnd.android.package-archive"

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 打开系统安装确认页；没有权限或没有安装器时返回 false。 */
    fun install(context: Context, file: File): Boolean {
        if (!file.isFile || !canInstall(context)) return false
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updates",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            launch(context, intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** 跳到本应用的“安装未知应用”开关；部分 ROM 需要回退到应用详情页。 */
    fun openInstallPermissionSettings(context: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            add(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        for (intent in candidates) {
            try {
                launch(context, intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // 换下一个候选页
            } catch (e: SecurityException) {
                // 换下一个候选页
            } catch (e: Exception) {
                // 换下一个候选页
            }
        }
        return false
    }

    fun openUrl(context: Context, url: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        return try {
            launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun launch(context: Context, intent: Intent) {
        if (context.findActivity() == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}
