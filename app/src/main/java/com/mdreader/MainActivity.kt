package com.mdreader

import android.graphics.Color
import android.os.Bundle
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mdreader.data.comics.AppContextHolder
import com.mdreader.data.comics.ComicHttp
import com.mdreader.ui.MDReaderApp
import com.mdreader.ui.theme.MDReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The BatCave challenge solver builds one offscreen WebView; it needs an application context.
        AppContextHolder.context = applicationContext
        // Cloudflare signs its clearance against the user agent, so the comic client speaks as
        // the device's real WebView — the engine that actually clears the challenge.
        ComicHttp.userAgent = WebSettings.getDefaultUserAgent(this)
            ?.takeIf { it.isNotBlank() }
            ?: ComicHttp.BROWSER_UA
        val transparent = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparent, navigationBarStyle = transparent)
        setContent {
            MDReaderTheme {
                MDReaderApp()
            }
        }
    }
}
