package cx.miruro.app.ui.components

import android.content.Context
import android.content.Intent

/** One share path for every detail screen, so the chooser and extras never drift apart. */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share"))
}
