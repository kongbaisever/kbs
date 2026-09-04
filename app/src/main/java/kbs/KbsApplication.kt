package kbs

import android.app.Application
import android.content.Context
import kbs.util.CrashHandler

class KbsApplication : Application() {

    /**
     * ★ 崩溃捕获装在 attachBaseContext，而非 onCreate。
     *
     * attachBaseContext 是应用进程中最早可执行用户代码的时机，
     * 早于所有 ContentProvider.onCreate，也早于 Application.onCreate。
     * 放在这里才能捕获到 ContentProvider 初始化、Application 自身的异常。
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base != null) CrashHandler.install(base)
    }

    override fun onCreate() {
        super.onCreate()
        // 兜底：install() 内部有幂等保护，重复调用无副作用
        CrashHandler.install(this)
    }
}
