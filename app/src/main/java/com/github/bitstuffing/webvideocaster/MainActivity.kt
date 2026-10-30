package com.github.bitstuffing.webvideocaster

import com.github.bitstuffing.webvideocaster.ui.main.MainScreen

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.github.bitstuffing.webvideocaster.runtime.CastService
import com.github.bitstuffing.webvideocaster.ui.theme.WebVideoCasterTheme
import com.github.bitstuffing.webvideocaster.utils.USER_AGENT
import com.github.bitstuffing.webvideocaster.utils.isMediaUrl
import androidx.lifecycle.lifecycleScope
import com.github.bitstuffing.webvideocaster.adblock.AdBlocker
import com.github.bitstuffing.webvideocaster.adblock.AdBlockRepository
import com.github.bitstuffing.webvideocaster.adblock.AdBlockSettings
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var serviceBound = false

    private val castServiceState = mutableStateOf<CastService?>(null)

    private var detectedVideos by mutableStateOf(listOf<String>())
    private var canGoBack by mutableStateOf(false)
    private var canGoForward by mutableStateOf(false)
    private var currentUrl by mutableStateOf("https://www.duckduckgo.com")
    private var urlInput by mutableStateOf("https://www.duckduckgo.com")

    private val adBlocker = AdBlocker()
    private lateinit var adBlockRepository: AdBlockRepository

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            Log.d("CAST_PERMISSION","Notification permission granted: $granted")
        }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CastService.LocalBinder
            castServiceState.value = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            castServiceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()

        val adBlockSettings = AdBlockSettings.load(this)

        adBlockRepository = AdBlockRepository(this, adBlocker)
        adBlocker.enabled = adBlockSettings.enabled

        adBlockRepository.loadCachedDomains()

        lifecycleScope.launch {
            adBlockRepository.refresh(adBlockSettings)
        }

        val serviceIntent = Intent(this, CastService::class.java)
        startService(serviceIntent)
        serviceBound = bindService(serviceIntent, connection, BIND_AUTO_CREATE)

        loadMainViews()
    }

    private fun loadMainViews(){
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val webContainer = FrameLayout(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = USER_AGENT

            setInitialScale(0)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false

            clearCache(true)
            clearHistory()

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    } else {
                        request.deny()
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {

                    val url = request?.url?.toString().orEmpty()

                    // 1. Block domains
                    if (url.isNotBlank() && adBlocker.isBlocked(url)) {
                        Log.d("ADBLOCK", "Blocked: $url")

                        return WebResourceResponse("text/plain","UTF-8",ByteArrayInputStream(ByteArray(0)))
                    }

                    // 2. media detection
                    if (url.isNotBlank() && isMediaUrl(url)) {
                        runOnUiThread {
                            addVideo(url)
                            Toast.makeText(this@MainActivity,R.string.video_detected,Toast.LENGTH_SHORT).show()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    canGoBack = view?.canGoBack() == true
                    canGoForward = view?.canGoForward() == true

                    url?.let {
                        currentUrl = it
                        urlInput = it
                    }
                }

                override fun onPageCommitVisible(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageCommitVisible(view, url)
                    view?.invalidate()
                }
            }
        }

        webContainer.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WebVideoCasterTheme {
                    MainScreen(
                        webView = webView,
                        detectedVideos = detectedVideos,
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        urlInput = urlInput,
                        onUrlInputChange = { urlInput = it },
                        onNavigate = { navigateToUrl() },
                        onClearVideos = { detectedVideos = emptyList() },
                        castServiceState = castServiceState
                    )
                }
            }
        }
        root.addView(composeView,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webContainer,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
        webView.loadUrl(currentUrl)
    }

    private fun navigateToUrl() {
        val address = urlInput.trim()
        if (address.isBlank()) return

        val url = when {
            address.startsWith("https://", true) -> address
            address.startsWith("http://", true) -> address
            else -> "https://$address"
        }

        currentUrl = url
        webView.loadUrl(url)
    }

    private fun addVideo(url: String) {
        if (url.isBlank() || detectedVideos.contains(url)) return

        detectedVideos = listOf(url) + detectedVideos
        Log.d("CAST_MAIN", "Added video: $url")
    }

    override fun onDestroy() {
        if (::webView.isInitialized && isFinishing) {
            webView.stopLoading()
            webView.destroy()
        }

        if (serviceBound) {
            try {
                unbindService(connection)
            } catch (e: IllegalArgumentException) {
                Log.e("CAST_MAIN", "Error - unbinding Cast", e)
            }
        }

        super.onDestroy()
    }
}