package com.kooritea.fcmfix.ui

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kooritea.fcmfix.ui.data.AppIcons
import com.kooritea.fcmfix.ui.data.ConfigStore
import com.kooritea.fcmfix.ui.data.InstalledApp
import com.kooritea.fcmfix.ui.data.InstalledApps
import com.kooritea.fcmfix.ui.data.ModuleConfig
import com.kooritea.fcmfix.ui.data.ReportExporter
import com.kooritea.fcmfix.ui.data.StatusQuery
import com.kooritea.fcmfix.ui.model.LocalConfig
import com.kooritea.fcmfix.ui.model.ReportEnv
import com.kooritea.fcmfix.ui.model.StatusReport
import com.kooritea.fcmfix.ui.model.StatusSnapshot
import com.kooritea.fcmfix.util.HookStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

sealed interface ExportState {
    data object Idle : ExportState

    /** The report can include one app's notification state and logs; offer the allowed apps. */
    data class ChooseApp(val apps: List<Pair<String, String>>) : ExportState

    data object Running : ExportState

    data class Failed(val message: String) : ExportState

    data class Done(val file: File) : ExportState
}

/**
 * State of the module app: the config it keeps, the installed apps, and the self-check answered
 * by the hooked system_server and Battery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application), AppActions {
    private val app: Application get() = getApplication()

    private val mutableConfig = MutableStateFlow<ModuleConfig?>(null)

    /** Null only until the preferences have been read. */
    val config: StateFlow<ModuleConfig?> = mutableConfig.asStateFlow()

    private val mutableApps = MutableStateFlow<List<InstalledApp>?>(null)

    /** Null while scanning; allowed apps first, in the order of the last scan. */
    val apps: StateFlow<List<InstalledApp>?> = mutableApps.asStateFlow()

    /**
     * The last status answer with the config as it was when the answer came: comparing it
     * with a config changed since would report a sync problem that is not there.
     */
    private data class Answer(val snapshot: StatusSnapshot, val local: LocalConfig?)

    private val answer = MutableStateFlow<Answer?>(null)

    /** Null until the first status query returns. */
    val report: StateFlow<StatusReport?> = combine(answer, mutableApps) { answer, apps ->
        answer to apps
    }.mapLatest { (answer, apps) ->
        answer?.let { buildReport(it, apps) }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    private val mutableScanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = mutableScanning.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** Short confirmations and errors for the snackbar. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val mutableConfigError = MutableStateFlow<String?>(null)

    /** A failed config write, shown in a dialog. */
    val configError: StateFlow<String?> = mutableConfigError.asStateFlow()

    private val mutableExport = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = mutableExport.asStateFlow()
    private var lastReport: File? = null

    private val mutableAppListUnreadable = MutableStateFlow(false)

    /** One app or none: the app-list permission was denied. */
    val appListUnreadable: StateFlow<Boolean> = mutableAppListUnreadable.asStateFlow()

    val icons = AppIcons(app.packageManager, (56 * app.resources.displayMetrics.density).roundToInt())

    /** BuildConfig is not generated for this module. */
    val versionName: String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        "?"
    }

    private var refreshJob: Job? = null
    private var delayedRefresh: Job? = null

    init {
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { ConfigStore.load(app) }
            mutableConfig.value = config
            // system_server keeps its own copy. Send ours at every start, so a module that was
            // just flashed gets the config; a fresh install has nothing to send and must not
            // overwrite the copy system_server still has.
            if (withContext(Dispatchers.IO) { ConfigStore.hasSaved(app) }) {
                ConfigStore.push(app, config)
                delay(800)
            }
            // The answer so far was compared with no config; ask again.
            refreshJob?.join()
            refreshStatus()
        }
        scanApps()
    }

    /** Called on every resume: pushes may have arrived, or a setting was changed elsewhere. */
    override fun refreshStatus() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableRefreshing.value = true
            try {
                val bundle = StatusQuery.query(app)
                val local = mutableConfig.value?.let { LocalConfig(it.allowList.size, it.autoAllowFcm) }
                answer.value = Answer(StatusSnapshot.from(bundle), local)
            } finally {
                mutableRefreshing.value = false
            }
        }
    }

    /** After a config change, once system_server has had a moment to take it in. */
    private fun refreshSoon() {
        delayedRefresh?.cancel()
        delayedRefresh = viewModelScope.launch {
            delay(800)
            refreshJob?.join()
            refreshStatus()
        }
    }

    override fun scanApps() {
        if (mutableScanning.value) return
        mutableScanning.value = true
        viewModelScope.launch {
            try {
                val scanned = withContext(Dispatchers.IO) { InstalledApps.scan(app.packageManager) }
                // Allowed apps go first, so wait briefly for the config; the order then stays put
                // while apps are ticked and unticked.
                val config = withTimeoutOrNull(5000) { mutableConfig.first { it != null } }
                mutableApps.value = scanned.sortedBy { config?.allows(it.packageName, it.includeFcm) != true }
                if (scanned.size <= 1) mutableAppListUnreadable.value = true
            } finally {
                mutableScanning.value = false
            }
        }
    }

    fun dismissAppListUnreadable() {
        mutableAppListUnreadable.value = false
    }

    private fun buildReport(answer: Answer, apps: List<InstalledApp>?): StatusReport {
        val labels = apps?.associate { it.packageName to it.label }
        val env = ReportEnv(
            sdkInt = Build.VERSION.SDK_INT,
            display = Build.DISPLAY,
            versionName = versionName,
            localConfig = answer.local,
            now = System.currentTimeMillis(),
            label = { name -> labels?.get(name) ?: label(name) },
            relativeTime = { time -> DateUtils.getRelativeTimeSpanString(time).toString() },
        )
        return StatusReport.build(env, answer.snapshot)
    }

    fun label(packageName: String): String = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    override fun setAllowed(target: InstalledApp, allowed: Boolean) =
        updateConfig { it.withAllowed(target.packageName, target.includeFcm, allowed) }

    override fun setAutoAllow(enabled: Boolean) = updateConfig { it.copy(autoAllowFcm = enabled) }

    override fun setKeepNotifications(enabled: Boolean) = updateConfig { it.copy(keepNotifications = enabled) }

    override fun setIceBox(enabled: Boolean) = updateConfig { it.copy(iceBox = enabled) }

    override fun allowAllFcm() {
        val fcm = apps.value?.filter { it.includeFcm }?.map { it.packageName } ?: return
        updateConfig { it.copy(allowList = it.allowList + fcm) }
    }

    private fun updateConfig(change: (ModuleConfig) -> ModuleConfig) {
        val current = mutableConfig.value
        if (current == null) {
            message("配置正在加载，请稍后再试")
            return
        }
        val next = change(current)
        if (next == current) return
        try {
            ConfigStore.save(app, next)
            mutableConfig.value = next
            refreshSoon()
        } catch (e: Throwable) {
            Log.e(TAG, "updateConfig", e)
            mutableConfigError.value = e.message ?: e.toString()
        }
    }

    fun dismissConfigError() {
        mutableConfigError.value = null
    }

    override fun copyText(): String? = report.value?.toText()

    override fun startExport() {
        val allowed = answer.value?.snapshot?.apps
        if (allowed.isNullOrEmpty()) {
            export(null)
        } else {
            val labels = apps.value?.associate { it.packageName to it.label }
            mutableExport.value = ExportState.ChooseApp(allowed.map { it to (labels?.get(it) ?: label(it)) })
        }
    }

    override fun export(packageName: String?) {
        if (mutableExport.value == ExportState.Running) return
        mutableExport.value = ExportState.Running
        viewModelScope.launch {
            // The module's own log lines outlive logcat; fetch them first, then append them.
            val checkText = report.value?.toText().orEmpty()
            val logs = StatusQuery.query(app, HookStatus.EXTRA_LOGS)
            val appendix = "\n== 自查结果\n" + checkText + "\n" + ReportExporter.logsText(logs)
            mutableExport.value = try {
                val file = ReportExporter.export(app, packageName, appendix)
                lastReport = file
                ExportState.Done(file)
            } catch (e: ReportExporter.Failure) {
                ExportState.Failed(e.message.orEmpty())
            }
        }
    }

    fun dismissExport() {
        if (mutableExport.value != ExportState.Running) mutableExport.value = ExportState.Idle
    }

    fun saveReport(uri: Uri?) {
        val report = lastReport
        if (uri == null || report == null) return
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val out = app.contentResolver.openOutputStream(uri) ?: throw IOException("无法写入")
                    out.use { output -> report.inputStream().use { it.copyTo(output) } }
                    null
                } catch (e: IOException) {
                    e.message ?: e.toString()
                }
            }
            message(if (error == null) "已保存" else "保存失败：$error")
        }
    }

    override fun message(text: String) {
        messageChannel.trySend(text)
    }

    private companion object {
        const val TAG = "FCMFix"
    }
}
