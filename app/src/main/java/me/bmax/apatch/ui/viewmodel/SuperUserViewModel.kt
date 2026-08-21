package me.bmax.apatch.ui.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import me.bmax.apatch.APApplication
import me.bmax.apatch.IAPRootService
import me.bmax.apatch.Natives
import me.bmax.apatch.apApp
import me.bmax.apatch.services.RootServices
import me.bmax.apatch.util.APatchCli
import me.bmax.apatch.util.HanziToPinyin
import me.bmax.apatch.util.PkgConfig
import java.text.Collator
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


class SuperUserViewModel : ViewModel() {
    companion object {
        private const val TAG = "SuperUserViewModel"
        private val appsLock = Any()
        var apps by mutableStateOf<List<AppInfo>>(emptyList())

        fun getAppIconDrawable(context: Context, packageName: String): Drawable? {
            val appList = synchronized(appsLock) { apps }
            val appDetail = appList.find { it.packageName == packageName }
            return appDetail?.packageInfo?.applicationInfo?.loadIcon(context.packageManager)
        }
    }

    @Parcelize
    data class AppInfo(
        val label: String,
        val pinyin: String,
        val packageInfo: PackageInfo,
        val config: PkgConfig.Config
    ) : Parcelable {
        val packageName: String
            get() = packageInfo.packageName
        val uid: Int
            get() = packageInfo.applicationInfo!!.uid
    }

    var search by mutableStateOf("")
    var showSystemApps by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)
        private set

    private val collator = Collator.getInstance(Locale.getDefault())

    private val sortedList by derivedStateOf {
        val comparator = compareBy<AppInfo> {
            when {
                it.config.allow != 0 -> 0
                it.config.exclude == 1 -> 1
                else -> 2
            }
        }.then(compareBy(collator, AppInfo::label))
        apps.sortedWith(comparator)
    }

    val appList by derivedStateOf {
        val query = search.lowercase()
        sortedList.filter {
            it.label.lowercase().contains(query) || it.packageName.lowercase()
                .contains(query) || it.pinyin.contains(query)
        }.filter {
            it.uid == 2000 // Always show shell
                    || showSystemApps || it.packageInfo.applicationInfo!!.flags.and(ApplicationInfo.FLAG_SYSTEM) == 0
        }.filter {
            it.packageName != apApp.packageName
        }
    }

    private suspend inline fun connectRootService(
        crossinline onDisconnect: () -> Unit = {}
    ): Pair<IBinder, ServiceConnection>? = withTimeoutOrNull(4000L) {
        suspendCoroutine { cont ->
            var resumed = false
            val connection = object : ServiceConnection {
                override fun onServiceDisconnected(name: ComponentName?) {
                    onDisconnect()
                }

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (!resumed) {
                        resumed = true
                        if (binder != null) {
                            cont.resume(binder to this)
                        } else {
                            cont.resume(null)
                        }
                    }
                }
            }
            try {
                val intent = Intent(apApp, RootServices::class.java)
                val task = RootServices.bindOrTask(
                    intent,
                    Shell.EXECUTOR,
                    connection,
                )
                val shell = me.bmax.apatch.util.getRootShell()
                if (task != null && shell.isAlive) {
                    shell.execTask(task)
                } else {
                    if (!resumed) {
                        resumed = true
                        cont.resume(null)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "connectRootService error", e)
                if (!resumed) {
                    resumed = true
                    cont.resume(null)
                }
            }
        }
    }

    private fun stopRootService() {
        val intent = Intent(apApp, RootServices::class.java)
        RootServices.stop(intent)
    }

    suspend fun fetchAppList() {
        if (isRefreshing) return
        isRefreshing = true

        try {
            withContext(Dispatchers.IO) {
                val pm = apApp.packageManager
                val isChinese = Locale.getDefault().language == "zh"
                val pinyinHelper = if (isChinese) HanziToPinyin.getInstance() else null

                // Fast in-process package loading (under 20ms)
                val installedPackages = try {
                    pm.getInstalledPackages(0)
                } catch (e: Exception) {
                    emptyList()
                }

                val uids = try {
                    Natives.suUids().toList()
                } catch (e: Exception) {
                    emptyList()
                }

                Natives.su()
                val configs = PkgConfig.readConfigs()

                val newApps = installedPackages.mapNotNull { pkgInfo ->
                    val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                    val uid = appInfo.uid
                    val actProfile = if (uids.contains(uid)) Natives.suProfile(uid) else null
                    val config = configs.getOrDefault(
                        uid, PkgConfig.Config(appInfo.packageName, Natives.isUidExcluded(uid), 0, Natives.Profile(uid = uid))
                    )
                    config.allow = 0

                    if (actProfile != null) {
                        config.allow = 1
                        config.profile = actProfile
                    }

                    val label = appInfo.nonLocalizedLabel?.toString() ?: appInfo.loadLabel(pm).toString()
                    val pinyin = if (pinyinHelper != null) pinyinHelper.toPinyinString(label) else ""

                    AppInfo(
                        label = label,
                        pinyin = pinyin,
                        packageInfo = pkgInfo,
                        config = config
                    )
                }

                withContext(Dispatchers.Main) {
                    synchronized(appsLock) {
                        apps = newApps
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch app list", e)
        } finally {
            isRefreshing = false
        }
    }

    // Replaces the app's config wholesale so the snapshot state holding `apps`
    // invalidates and the UI recomposes; mutating Config fields in place would
    // leave the list showing stale grant/exclude state after a refresh.
    private fun updateAppConfig(app: AppInfo, newConfig: PkgConfig.Config) {
        synchronized(appsLock) {
            // Grant/exclude are per-UID operations; every package sharing the
            // UID must show the new state, or its stale row could overwrite it.
            apps = apps.map {
                if (it.uid == app.uid) it.copy(config = newConfig.copy(pkg = it.packageName)) else it
            }
        }
    }

    fun setRootGranted(app: AppInfo, granted: Boolean) {
        val config = app.config
        val newConfig = if (granted) {
            config.copy(
                allow = 1,
                exclude = 0,
                profile = config.profile.copy(uid = app.uid, scontext = APApplication.MAGISK_SCONTEXT)
            )
        } else {
            config.copy(allow = 0, profile = config.profile.copy(uid = app.uid))
        }
        PkgConfig.changeConfig(newConfig)
        if (granted) {
            Natives.grantSu(app.uid, 0, newConfig.profile.scontext)
            Natives.setUidExclude(app.uid, 0)
        } else {
            Natives.revokeSu(app.uid)
        }
        updateAppConfig(app, newConfig)
    }

    fun setExcluded(app: AppInfo, excluded: Boolean) {
        val config = app.config
        val newConfig = if (excluded) {
            config.copy(
                allow = 0,
                exclude = 1,
                profile = config.profile.copy(uid = app.uid, scontext = APApplication.DEFAULT_SCONTEXT)
            )
        } else {
            config.copy(exclude = 0, profile = config.profile.copy(uid = app.uid))
        }
        if (excluded) {
            Natives.revokeSu(app.uid)
        }
        PkgConfig.changeConfig(newConfig)
        Natives.setUidExclude(app.uid, newConfig.exclude)
        updateAppConfig(app, newConfig)
    }
}
