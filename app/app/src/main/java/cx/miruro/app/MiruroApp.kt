package cx.miruro.app

import android.app.Application
import cx.miruro.app.data.AutoSkipStore
import cx.miruro.app.data.FranchiseStore
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.PlaybackPrefsStore
import cx.miruro.app.data.ProgressStore
import cx.miruro.app.data.SourceStore
import cx.miruro.app.data.StyleStore
import cx.miruro.app.data.UpdateChecker
import java.io.File

class MiruroApp : Application() {
    lateinit var sources: SourceStore
        private set
    val franchises = FranchiseStore()
    lateinit var updates: UpdateChecker
        private set
    lateinit var api: MiruroApi
        private set
    lateinit var progress: ProgressStore
        private set
    lateinit var styles: StyleStore
        private set
    lateinit var prefs: PlaybackPrefsStore
        private set
    lateinit var autoSkip: AutoSkipStore
        private set

    override fun onCreate() {
        super.onCreate()
        sources =
            SourceStore(
                file = File(filesDir, "sources.json"),
                legacyFile = File(filesDir, "endpoint.txt")
            )
        api = MiruroApi(sources)
        progress = ProgressStore(File(filesDir, "progress.json"))
        styles = StyleStore(File(filesDir, "subtitle-style.json"))
        prefs = PlaybackPrefsStore(File(filesDir, "track-preference.txt"))
        autoSkip = AutoSkipStore(File(filesDir, "auto-skip.json"))
        updates = UpdateChecker(this)
        updates.checkInBackground(
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            )
        )
    }
}
