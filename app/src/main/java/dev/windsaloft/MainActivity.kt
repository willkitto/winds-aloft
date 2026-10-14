package dev.windsaloft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.windsaloft.ui.WindsAloftApp
import dev.windsaloft.ui.WindsAloftTheme
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            // Tiles live in app storage and are reused offline. Keep up to ~1 GB.
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(filesDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 1024L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 800L * 1024 * 1024
        }
        enableEdgeToEdge()
        setContent {
            WindsAloftTheme { WindsAloftApp(vm) }
        }
    }
}
