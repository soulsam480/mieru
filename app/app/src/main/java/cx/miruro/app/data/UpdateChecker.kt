package cx.miruro.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tag: String = "",
    val name: String? = null,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0
)

data class UpdateInfo(val version: String, val apkUrl: String, val notes: String?)

/**
 * The app is not on any store, so updates come straight from GitHub releases. This checks the
 * latest tag and hands back the APK URL when it is newer than what is installed.
 */
class UpdateChecker(private val context: Context) {

    /** Latest known newer release, if any. Observed by the UI to show a banner. */
    val available = kotlinx.coroutines.flow.MutableStateFlow<UpdateInfo?>(null)

    /** Checks at most once a day, off the main thread. */
    fun checkInBackground(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            val stampFile = File(context.filesDir, "update-check.txt")
            val last = stampFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - last < ONE_DAY_MILLIS) return@launch

            check().onSuccess { info ->
                available.value = info
                runCatching { stampFile.writeText(System.currentTimeMillis().toString()) }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Plain client: the Miruro interceptors would send Referer/Sec-Fetch-* to the GitHub API.
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

    val installedVersion: String
        get() =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "0"

    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val request =
                Request.Builder()
                    .url("$API/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MiruroClient")
                    .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub answered ${response.code}")
                }
                val release = json.decodeFromString<GithubRelease>(response.body.string())
                val version = release.tag.removePrefix("v")
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", true) }

                if (apk == null || !isNewerVersion(version, installedVersion)) {
                    null
                } else {
                    UpdateInfo(version = version, apkUrl = apk.url, notes = release.body)
                }
            }
        }
    }

    /** Downloads the APK into cache and returns the file. */
    suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "miruro-${info.version}.apk")

        val request = Request.Builder().url(
            info.apkUrl
        ).header("User-Agent", "MiruroClient").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Download failed: ${response.code}"
                )
            }
            response.body.byteStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target
    }

    fun install(apk: File) {
        val uri: Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    private companion object {
        const val API = "https://api.github.com/repos/soulsam480/mireru/releases"
        const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
