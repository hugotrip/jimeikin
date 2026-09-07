package com.wanderwildwood.jimeikin.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD

private const val YOUTUBE_MUSIC_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
private const val YOUTUBE_MUSIC_HOST = "music.youtube.com"
private const val YOUTUBE_MUSIC_COOKIE_URL = "https://music.youtube.com"

/**
 * Lets the user sign in to their own YouTube account by loading Google's real
 * sign-in page in a WebView. The user enters their credentials directly into
 * Google's page; this screen only reads back the resulting session cookie
 * from [CookieManager] once the WebView lands on a signed-in music.youtube.com
 * page, then hands that cookie to [onLoginSuccess].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginScreen(
    onLoginSuccess: (cookie: String) -> Unit,
    onBackClick: () -> Unit,
) {
    var isCompletingLogin by remember { mutableStateOf(false) }
    var rendererGone by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var webView: WebView? = null

    fun completeLoginIfSignedIn() {
        if (isCompletingLogin) return
        val cookie = CookieManager.getInstance().getCookie(YOUTUBE_MUSIC_COOKIE_URL).orEmpty()
        if (cookie.isBlank() || !cookie.contains("SAPISID")) return

        isCompletingLogin = true
        webView?.apply {
            stopLoading()
            clearHistory()
        }
        onLoginSuccess(cookie)
    }

    if (rendererGone) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TextMMD(
                text = "The sign-in page did not load",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            ButtonMMD(onClick = {
                rendererGone = false
                retryKey++
            }) {
                TextMMD(text = "Retry")
            }
        }
    } else {
        key(retryKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (url?.contains(YOUTUBE_MUSIC_HOST) == true) {
                                    completeLoginIfSignedIn()
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                webView = null
                                rendererGone = true
                                return true
                            }
                        }
                        settings.apply {
                            javaScriptEnabled = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                        webView = this
                        loadUrl(YOUTUBE_MUSIC_LOGIN_URL)
                    }
                },
            )
        }
    }

    BackHandler {
        val currentWebView = webView
        if (currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else {
            onBackClick()
        }
    }
}
