package cx.miruro.app

import android.app.Application
import cx.miruro.app.data.EndpointStore
import cx.miruro.app.data.FranchiseStore
import cx.miruro.app.data.MiruroApi
import cx.miruro.app.data.PlaybackPrefsStore
import cx.miruro.app.data.ProgressStore
import cx.miruro.app.data.StyleStore
import cx.miruro.app.data.UpdateChecker
import java.io.File

class MiruroApp : Application() {
    lateinit var endpoints: EndpointStore
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

    override fun onCreate() {
        super.onCreate()
        endpoints = EndpointStore(File(filesDir, "endpoint.txt"))
        api = MiruroApi(endpoints)
        progress = ProgressStore(File(filesDir, "progress.json"))
        styles = StyleStore(File(filesDir, "subtitle-style.json"))
        prefs = PlaybackPrefsStore(File(filesDir, "track-preference.txt"))
        updates = UpdateChecker(this)
        updates.checkInBackground(
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            )
        )
    }
}
