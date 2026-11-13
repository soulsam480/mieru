package cx.miruro.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cx.miruro.app.ui.MiruroNavHost
import cx.miruro.app.ui.MiruroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiruroTheme {
                MiruroNavHost(application as MiruroApp)
            }
        }
    }
}
