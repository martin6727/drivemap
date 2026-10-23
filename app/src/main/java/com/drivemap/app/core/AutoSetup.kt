package com.drivemap.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Android Auto (17+) lists a navigation app in a real car only when it looks like a Play Store
 * install — its "Unknown sources" switch no longer covers this kind of app.
 *
 * Fix: reinstall DriveMap through the phone's own installer with the Play Store named as the
 * installer. Updates go the same way, so they keep working in the car.
 */
object AutoSetup {
    private const val PLAY = "com.android.vending"
    private const val SYSTEM_INSTALLER = "com.google.android.packageinstaller"

    /** True while the "enable Android Auto" prompt should show. */
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt

    /** Waiting for the user to allow "install unknown apps"; this APK installs once they return. */
    private var pendingApk: File? = null

    /** Will Android Auto show DriveMap in the car? */
    fun ready(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        if (Build.VERSION.SDK_INT >= 30) {
            val s = pm.getInstallSourceInfo(ctx.packageName)
            when (s.initiatingPackageName) {
                PLAY -> true
                SYSTEM_INSTALLER -> s.installingPackageName == PLAY
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(ctx.packageName) == PLAY
        }
    } catch (_: Exception) {
        false
    }

    /** Ask once per install whether to set up Android Auto. */
    fun checkOnStart(ctx: Context) {
        if (ready(ctx)) return
        val p = prefs(ctx)
        val stamp = installStamp(ctx)
        if (p.getLong("asked", 0) == stamp) return
        p.edit().putLong("asked", stamp).apply()
        _prompt.value = true
    }

    fun openPrompt() { _prompt.value = true }
    fun dismissPrompt() { _prompt.value = false }

    /** Reinstall this same version so Android Auto accepts it. */
    fun enable(ctx: Context) {
        _prompt.value = false
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        val apk = File(dir, "DriveMap.apk")
        File(ctx.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        install(ctx, apk)
    }

    /** Install [apk] (this app or an update) marked as a Play Store install. */
    fun install(ctx: Context, apk: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        pendingApk = null
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        ctx.startActivity(i)
    }

    /** Back from the "install unknown apps" screen: carry on if it was allowed. */
    fun onResume(ctx: Context) {
        val apk = pendingApk ?: return
        if (ctx.packageManager.canRequestPackageInstalls()) install(ctx, apk) else pendingApk = null
    }

    private fun installStamp(ctx: Context): Long =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (_: Exception) { 0 }

    private fun prefs(c: Context) = c.getSharedPreferences("autosetup", Context.MODE_PRIVATE)
}
