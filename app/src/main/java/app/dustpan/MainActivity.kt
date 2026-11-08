package app.dustpan

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.dustpan.ui.DustpanScreen
import app.dustpan.ui.DustpanTheme
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

class MainActivity : ComponentActivity() {

    private val viewModel: DustpanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Has to be created before the activity is STARTED: it registers an activity-result launcher.
        val sender = ActivityResultSender(this)

        setContent {
            DustpanTheme {
                DustpanScreen(viewModel = viewModel, sender = sender)
            }
        }
    }
}
