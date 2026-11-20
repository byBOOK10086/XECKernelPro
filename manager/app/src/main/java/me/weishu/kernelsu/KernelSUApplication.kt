package me.weishu.kernelsu

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.system.Os
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

lateinit var ksuApp: KernelSUApplication

open class KernelSUApplication : Application(), ViewModelStoreOwner {
    private var themeTextResources: me.weishu.kernelsu.data.theme.ThemeTextResources? = null
    override fun getResources(): android.content.res.Resources {
        val original = super.getResources()
        return themeTextResources?.takeIf { it.source === original && it.configuration == original.configuration }
            ?: me.weishu.kernelsu.data.theme.ThemeTextResources(original, live = true).also { themeTextResources = it }
    }


    companion object {
        fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
            runCatching {
                val applicationInfoClass = ApplicationInfo::class.java
                val method = applicationInfoClass.getDeclaredMethod("setEnableOnBackInvokedCallback", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(appInfo, enable)
            }
        }
    }

    lateinit var okhttpClient: OkHttpClient
    private val appViewModelStore by lazy { ViewModelStore() }

    private fun isUserUnlocked(): Boolean =
        getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun onCreate() {
        // 31438：任何未捕获异常都落一份到 filesDir/crash/last-crash.txt，方便用户把
        // 「模块页授权闪退 / 发送日志闪退」这类没有界面的崩溃直接发回来定位。
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val dir = java.io.File(filesDir, "crash").apply { mkdirs() }
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date())
                    java.io.File(dir, "last-crash.txt").writeText(
                        "time: $stamp\nthread: ${thread.name}\n\n" +
                            android.util.Log.getStackTraceString(throwable),
                    )
                }
                previous?.uncaughtException(thread, throwable)
            }
        }

        super.onCreate()
        ksuApp = this

        if (!isUserUnlocked()) {
            return
        }

        me.weishu.kernelsu.data.theme.ThemeTextRuntime.refresh(this)

        // 2026-11-18 磁吸磁贴板已下线：删掉它写进 settings 的布局/颜色记忆，
        // 界面恢复成原先的卡片首页，不再"记得"任何磁吸状态。
        runCatching {
            getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
                .remove("home_layout_tiles")
                .remove("home_tile_layout_v2")
                .remove("home_tile_roles")
                .apply()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val enable = SettingsRepositoryImpl().enablePredictiveBack
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback")
            setEnableOnBackInvokedCallback(applicationInfo, enable)
        }

        val superUserViewModel = ViewModelProvider(this)[SuperUserViewModel::class.java]
        superUserViewModel.loadAppList()

        val webroot = File(dataDir, "webroot")
        if (!webroot.exists()) {
            webroot.mkdir()
        }

        // Provide working env for rust's temp_dir()
        Os.setenv("TMPDIR", cacheDir.absolutePath, true)

        okhttpClient =
            OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
                .addInterceptor { block ->
                    block.proceed(
                        block.request().newBuilder()
                            .header("User-Agent", "XECKernel Ultra/${BuildConfig.VERSION_CODE}")
                            .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                    )
                }.build()
    }

    override val viewModelStore: ViewModelStore
        get() = appViewModelStore
}
