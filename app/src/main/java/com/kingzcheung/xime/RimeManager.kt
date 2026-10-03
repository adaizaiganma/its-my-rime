package com.kingzcheung.xime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kingzcheung.xime.rime.RimeEngine
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

data class EngineStatus(val ready: Boolean, val busy: Boolean, val message: String)

/** One worker owns all calls to librime so deployment and typing never race. */
object RimeManager {
    private const val TAG = "RimeManager"
    private const val SCHEMA = "rime_ice"
    private const val CONVERSION_ASSETS_VERSION = "s2tw-opencc-1.1.9-1"
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "rime-worker").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(EngineStatus) -> Unit>()
    private val engine = RimeEngine.getInstance()

    @Volatile var status = EngineStatus(false, true, "準備霧凇拼音…")
        private set
    @Volatile private var started = false

    fun observe(listener: (EngineStatus) -> Unit) {
        listeners += listener
        main.post { listener(status) }
    }

    fun removeObserver(listener: (EngineStatus) -> Unit) {
        listeners -= listener
    }

    @Synchronized fun ensureReady(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        worker.execute {
            try {
                setStatus(false, true, "安裝霧凇拼音資源…")
                val shared = File(app.filesDir, "rime-shared")
                val user = File(app.filesDir, "rime-user")
                shared.mkdirs()
                user.mkdirs()
                val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toString()
                val assetsMarker = File(shared, ".asset-version")
                val deploymentMarker = File(user, ".deployed-version")
                val conversionMarker = File(shared, ".conversion-assets-version")
                val assetsChanged = assetsMarker.takeIf { it.exists() }?.readText() != version
                val conversionChanged = conversionMarker.takeIf { it.exists() }?.readText() != CONVERSION_ASSETS_VERSION
                if (assetsChanged) {
                    copyAssets(app, "rime", shared)
                    assetsMarker.writeText(version)
                } else if (conversionChanged) {
                    listOf(
                        "rime_ice.schema.yaml",
                        "opencc/s2tw.json",
                        "opencc/STCharacters.txt",
                        "opencc/STPhrases.txt",
                        "opencc/TWVariants.txt"
                    ).forEach { path -> copyAssets(app, "rime/$path", File(shared, path)) }
                }
                val custom = File(user, "default.custom.yaml")
                if (!custom.exists()) {
                    custom.writeText("patch:\n  schema_list:\n    - schema: rime_ice\n")
                }

                val quickPhrasesChanged = QuickPhrases.syncRimeFile(app, shared, user)

                setStatus(false, true, "啟動 Rime 引擎…")
                engine.initialize(user.absolutePath, shared.absolutePath)
                check(RimeEngine.isInitialized()) { "Rime 引擎初始化失敗" }
                if (assetsChanged || conversionChanged || quickPhrasesChanged ||
                    deploymentMarker.takeIf { it.exists() }?.readText() != version) {
                    setStatus(false, true, "首次部署詞庫，請稍候…")
                    check(engine.deploy()) { "霧凇拼音部署失敗" }
                    deploymentMarker.writeText(version)
                    conversionMarker.writeText(CONVERSION_ASSETS_VERSION)
                }
                check(engine.ensureSession()) { "Rime 無法建立輸入會話" }
                check(engine.switchSchema(SCHEMA)) { "找不到霧凇拼音方案" }
                engine.setOption("ascii_mode", false)
                setStatus(true, false, "霧凇拼音已就緒")
            } catch (error: Throwable) {
                Log.e(TAG, "Rime startup failed", error)
                setStatus(false, false, error.message ?: "Rime 啟動失敗")
            }
        }
    }

    fun redeploy(context: Context) {
        ensureReady(context)
        worker.execute {
            try {
                setStatus(false, true, "正在重新部署 Rime…")
                QuickPhrases.syncRimeFile(context, File(context.filesDir, "rime-shared"),
                    File(context.filesDir, "rime-user"))
                check(engine.deploy()) { "重新部署失敗" }
                check(engine.ensureSession()) { "Rime 無法建立輸入會話" }
                check(engine.switchSchema(SCHEMA)) { "找不到霧凇拼音方案" }
                engine.setOption("ascii_mode", false)
                val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
                File(context.filesDir, "rime-user/.deployed-version").writeText(version)
                setStatus(true, false, "重新部署完成")
            } catch (error: Throwable) {
                Log.e(TAG, "Rime redeployment failed", error)
                setStatus(false, false, error.message ?: "重新部署失敗")
            }
        }
    }

    fun <T> run(context: Context, action: (RimeEngine) -> T, callback: (Result<T>) -> Unit) {
        ensureReady(context)
        worker.execute {
            val result = runCatching {
                check(status.ready) { status.message }
                action(engine)
            }
            main.post { callback(result) }
        }
    }

    private fun setStatus(ready: Boolean, busy: Boolean, message: String) {
        status = EngineStatus(ready, busy, message)
        main.post {
            val snapshot = status
            listeners.forEach { it(snapshot) }
        }
    }

    private fun copyAssets(context: Context, path: String, destination: File) {
        val entries = context.assets.list(path).orEmpty()
        if (entries.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { input -> destination.outputStream().use(input::copyTo) }
        } else {
            destination.mkdirs()
            entries.forEach { child -> copyAssets(context, "$path/$child", File(destination, child)) }
        }
    }
}
