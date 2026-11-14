package com.slate.launcher

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.slate.launcher.data.WidgetRepository
import com.slate.launcher.ui.LauncherRoot
import com.slate.launcher.ui.theme.SlateTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            SlateTheme { LauncherRoot(viewModel) }
        }
    }

    /** A widget's own setup screen finished (started via AppWidgetHost, so it reports here). */
    @Deprecated("Needed for AppWidgetHost.startAppWidgetConfigureActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WidgetRepository.REQUEST_CONFIGURE) {
            viewModel.onWidgetConfigured(resultCode == RESULT_OK)
        }
    }

    /** The system re-delivers HOME here when the Home button / gesture is used while we're in front. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            viewModel.onHomePressed()
        }
    }
}
