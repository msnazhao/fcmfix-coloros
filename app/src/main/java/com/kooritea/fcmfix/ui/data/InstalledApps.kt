package com.kooritea.fcmfix.ui.data

import android.content.pm.PackageManager
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.kooritea.fcmfix.util.FcmApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class InstalledApp(val packageName: String, val label: String, val includeFcm: Boolean)

object InstalledApps {
    /**
     * Every installed package and whether it can receive FCM. This takes seconds on a phone
     * with hundreds of system packages, so call it off the main thread. Apps with FCM come first.
     * "Can receive FCM" is FcmApps.scan, the rule system_server's auto-allow uses, so the count
     * here and the one on the status page cannot differ.
     */
    @Suppress("DEPRECATION") // The flag overload needs API 33; minSdk is 29.
    fun scan(pm: PackageManager): List<InstalledApp> {
        val fcm = FcmApps.scan(pm)
        val apps = ArrayList<InstalledApp>()
        for (info in pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            val label = info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName
            apps += InstalledApp(info.packageName, label, info.packageName in fcm)
        }
        val collator = Collator.getInstance(Locale.getDefault())
        return apps.sortedWith(compareBy<InstalledApp> { !it.includeFcm }.thenBy(collator) { it.label })
    }
}

/** App icons for the list, loaded on demand; a placeholder is drawn while one is missing. */
interface IconSource {
    fun cached(packageName: String): ImageBitmap?

    suspend fun load(packageName: String): ImageBitmap?

    object None : IconSource {
        override fun cached(packageName: String): ImageBitmap? = null
        override suspend fun load(packageName: String): ImageBitmap? = null
    }
}

class AppIcons(private val pm: PackageManager, private val sizePx: Int) : IconSource {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    override fun cached(packageName: String): ImageBitmap? = cache.get(packageName)

    override suspend fun load(packageName: String): ImageBitmap? = cached(packageName) ?: withContext(Dispatchers.IO) {
        val drawable = try {
            pm.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return@withContext null
        }
        val bitmap = createBitmap(sizePx, sizePx)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap().also { cache.put(packageName, it) }
    }
}
