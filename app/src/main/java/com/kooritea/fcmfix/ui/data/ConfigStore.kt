package com.kooritea.fcmfix.ui.data

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kooritea.fcmfix.util.ConfigSnapshot

/** The options the user set; system_server reads them through ConfigSnapshot. */
data class ModuleConfig(
    val allowList: Set<String> = emptySet(),
    /** FCM apps unticked while auto-allow is on. */
    val excludeList: Set<String> = emptySet(),
    val autoAllowFcm: Boolean = false,
    /** disableAutoCleanNotification */
    val keepNotifications: Boolean = false,
    /** includeIceBoxDisableApp */
    val iceBox: Boolean = false,
) {
    /** Same rule as ConfigSnapshot.allows in system_server. */
    fun allows(packageName: String, includeFcm: Boolean): Boolean =
        packageName in allowList || (autoAllowFcm && includeFcm && packageName !in excludeList)

    /** Allowed by the auto-allow switch rather than ticked by hand. */
    fun allowsAutomatically(packageName: String, includeFcm: Boolean): Boolean =
        packageName !in allowList && allows(packageName, includeFcm)

    /**
     * With auto-allow on, an FCM app is ticked by leaving the exclude list and unticked by
     * joining it; other apps use the manual allow list either way.
     */
    fun withAllowed(packageName: String, includeFcm: Boolean, allowed: Boolean): ModuleConfig {
        val automatic = autoAllowFcm && includeFcm
        return if (allowed) {
            copy(
                excludeList = excludeList - packageName,
                allowList = if (automatic) allowList else allowList + packageName,
            )
        } else {
            copy(
                allowList = allowList - packageName,
                excludeList = if (automatic) excludeList + packageName else excludeList,
            )
        }
    }
}

/**
 * The config lives in this app's own preferences. system_server keeps its own copy on disk and
 * only changes it when this app pushes a new one, so the two can be told apart in the status.
 */
object ConfigStore {
    private const val PREFERENCES = "config"
    private const val SAVED = "init"

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** False on a fresh install, and after the app's data was cleared. */
    fun hasSaved(context: Context): Boolean = preferences(context).getBoolean(SAVED, false)

    fun load(context: Context): ModuleConfig {
        val preferences = preferences(context)
        return ModuleConfig(
            allowList = preferences.getStringSet(ConfigSnapshot.ALLOW_LIST, null).orEmpty().toSet(),
            excludeList = preferences.getStringSet(ConfigSnapshot.EXCLUDE_LIST, null).orEmpty().toSet(),
            autoAllowFcm = preferences.getBoolean(ConfigSnapshot.AUTO_ALLOW_FCM, false),
            keepNotifications = preferences.getBoolean(ConfigSnapshot.KEEP_NOTIFICATIONS, false),
            iceBox = preferences.getBoolean(ConfigSnapshot.ICE_BOX, false),
        )
    }

    /** Saves the config here, then tells system_server. Throws when the write fails. */
    fun save(context: Context, config: ModuleConfig) {
        val saved = preferences(context).edit()
            .putBoolean(SAVED, true)
            .putStringSet(ConfigSnapshot.ALLOW_LIST, HashSet(config.allowList))
            .putStringSet(ConfigSnapshot.EXCLUDE_LIST, HashSet(config.excludeList))
            .putBoolean(ConfigSnapshot.AUTO_ALLOW_FCM, config.autoAllowFcm)
            .putBoolean(ConfigSnapshot.KEEP_NOTIFICATIONS, config.keepNotifications)
            .putBoolean(ConfigSnapshot.ICE_BOX, config.iceBox)
            .commit()
        check(saved) { "配置写入失败" }
        push(context, config)
    }

    /**
     * Sends the whole config to system_server with this app's identity attached, which is what
     * system_server checks. Nothing answers when the module is not loaded; the status shows that.
     */
    fun push(context: Context, config: ModuleConfig) {
        if (Build.VERSION.SDK_INT < 34) return
        val intent = Intent(context.packageName + ".update.config")
            .putExtra(ConfigSnapshot.ALLOW_LIST, config.allowList.toTypedArray())
            .putExtra(ConfigSnapshot.EXCLUDE_LIST, config.excludeList.toTypedArray())
            .putExtra(ConfigSnapshot.AUTO_ALLOW_FCM, config.autoAllowFcm)
            .putExtra(ConfigSnapshot.KEEP_NOTIFICATIONS, config.keepNotifications)
            .putExtra(ConfigSnapshot.ICE_BOX, config.iceBox)
        context.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }
}
