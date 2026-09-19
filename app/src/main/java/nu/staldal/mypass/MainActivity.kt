package nu.staldal.mypass

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import nu.staldal.mypass.ui.MyPassApp
import nu.staldal.mypass.ui.theme.MyPassTheme

/**
 * The one activity.
 *
 * A [FragmentActivity] rather than a bare `ComponentActivity` because
 * `androidx.biometric.BiometricPrompt` needs one; the UI is Compose either
 * way.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep passwords out of screenshots, the recents thumbnail and screen
        // recordings.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            MyPassTheme {
                MyPassApp()
            }
        }
    }
}
