package com.github.bitstuffing.webvideocaster.ui.main

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.bitstuffing.webvideocaster.MainActivity
import com.github.bitstuffing.webvideocaster.R
import com.github.bitstuffing.webvideocaster.runtime.CastService
import com.github.bitstuffing.webvideocaster.utils.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainScreen() {

    val context = LocalContext.current
    val activity = context as MainActivity
    val castService = activity.castServiceState.value
    val scope = rememberCoroutineScope()

    // to avoid ide errors
    val videoDetectedText = stringResource(R.string.video_detected)
    val noDeviceSelectedText = stringResource(R.string.no_device_selected)
    val videoClipLabelText = stringResource(R.string.video_clip_label)

    // ─────────────────────────────
    // WEB STATE
    // ─────────────────────────────
    var urlInput by remember { mutableStateOf("https://www.google.com") }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var pageTitle by remember { mutableStateOf("") }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    // ─────────────────────────────
    // CAST STATE
    // ─────────────────────────────
    var showCastDialog by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<CastSession?>(null) }
    var connected by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<CastDevice?>(null) }


    // ─────────────────────────────
    // VIDEO STATE
    // ─────────────────────────────
    var detectedVideos by remember { mutableStateOf(listOf<String>()) }
    var selectedVideo by remember { mutableStateOf<String?>(null) }
    var showVideoMenu by remember { mutableStateOf(false) }

    // ─────────────────────────────
    // DRAWER STATE
    // ─────────────────────────────
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    fun addVideo(url: String) {
        if (url.isBlank()) return
        if (detectedVideos.contains(url)) return
        detectedVideos = listOf(url) + detectedVideos
    }

    // ─────────────────────────────
    // DRAWER UI
    // ─────────────────────────────
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {

            ModalDrawerSheet {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        stringResource(R.string.videos_detected),
                        style = MaterialTheme.typography.titleMedium
                    )

                    IconButton(
                        onClick = {
                            detectedVideos = emptyList()
                            selectedVideo = null
                            showVideoMenu = false
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.clean_history)
                        )
                    }
                }

                if (detectedVideos.isEmpty()) {
                    Text(
                        stringResource(R.string.no_videos_yet),
                        modifier = Modifier.padding(12.dp)
                    )
                } else {

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp)
                    ) {

                        items(detectedVideos) { url ->

                            Surface(
                                tonalElevation = 2.dp,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        selectedVideo = url
                                        showVideoMenu = true
                                    }
                            ) {
                                Text(
                                    text = url,
                                    maxLines = 1,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {

        // ─────────────────────────────
        // MAIN UI
        // ─────────────────────────────
        Scaffold(

            topBar = {

                TopAppBar(

                    title = { Text(stringResource(R.string.app_name)) },

                    actions = {

                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (drawerState.isClosed) drawerState.open()
                                    else drawerState.close()
                                }
                            }
                        ) {
                            Icon(Icons.Filled.List, stringResource(R.string.videos))
                        }

                        IconButton(
                            onClick = {
                                showCastDialog = true
                                isSearching = true
                                devices = emptyList()

                                scope.launch {
                                    val lock = CastUtils.acquireMulticastLock(context)
                                    try {
                                        devices = CastUtils.searchDevices(context, 5000L)
                                    } finally {
                                        lock.release()
                                        isSearching = false
                                    }
                                }
                            }
                        ) {
                            Icon(
                                if (connected) Icons.Filled.CastConnected
                                else Icons.Filled.Cast,
                                contentDescription = stringResource(R.string.cast),
                                tint = if (connected) Color(0xFF4CAF50)
                                else LocalContentColor.current
                            )
                        }
                    }
                )
            }

        ) { padding ->

            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {

                // ─────────────────────────────
                // NAV BAR
                // ─────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp)
                ) {

                    IconButton({ webView?.goBack() }, enabled = canGoBack) {
                        Icon(Icons.Filled.ArrowBack, null)
                    }

                    IconButton({ webView?.goForward() }, enabled = canGoForward) {
                        Icon(Icons.Filled.ArrowForward, null)
                    }

                    IconButton({ webView?.reload() }) {
                        Icon(Icons.Filled.Refresh, null)
                    }

                    TextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            val finalUrl =
                                if (urlInput.startsWith("http")) urlInput
                                else "https://$urlInput"

                            currentUrl = finalUrl
                            webView?.loadUrl(finalUrl)
                        }
                    ) {
                        Text(stringResource(R.string.go))
                    }
                }

                if (pageTitle.isNotBlank()) {
                    Text(pageTitle, Modifier.padding(8.dp))
                }

                // ─────────────────────────────
                // WEBVIEW
                // ─────────────────────────────
                AndroidView(
                    factory = { ctx ->

                        WebView(ctx).apply {

                            webView = this

                            settings.javaScriptEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            // DRM
                            settings.userAgentString = USER_AGENT
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.mediaPlaybackRequiresUserGesture = false

                            webChromeClient = object : WebChromeClient() {

                                private var customView: View? = null
                                private var customViewCallback: CustomViewCallback? = null

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                }

                                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                    // VIDEO FULLSCREEN
                                    if (customView != null) {
                                        callback?.onCustomViewHidden()
                                        return
                                    }

                                    customView = view
                                    customViewCallback = callback

                                    (activity.window.decorView as FrameLayout).addView(
                                        customView,
                                        FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                }

                                override fun onHideCustomView() {
                                    val decor = activity.window.decorView as FrameLayout

                                    customView?.let {
                                        decor.removeView(it)
                                    }

                                    customView = null
                                    customViewCallback?.onCustomViewHidden()
                                }
                            }

                            webViewClient = object : WebViewClient() {

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = false

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {

                                    val url = request?.url.toString()

                                    if (isMediaUrl(url)) {
                                        activity.runOnUiThread {
                                            addVideo(url)
                                            Toast.makeText(
                                                activity,
                                                videoDetectedText,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }

                                    return super.shouldInterceptRequest(view, request)
                                }

                                override fun onPageCommitVisible(view: WebView?, url: String?) {
                                    super.onPageCommitVisible(view, url)
                                    view?.invalidate()
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?
                                ) {
                                    super.onPageFinished(view, url)

                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true

                                    url?.let {
                                        urlInput = it
                                        currentUrl = it
                                    }
                                }
                            }

                            loadUrl(currentUrl)
                        }
                    },
                    update = {
                        webView = it
                        canGoBack = it.canGoBack()
                        canGoForward = it.canGoForward()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // ─────────────────────────────
    // VIDEO MENU
    // ─────────────────────────────
    DropdownMenu(
        expanded = showVideoMenu,
        onDismissRequest = { showVideoMenu = false }
    ) {

        selectedVideo?.let { url ->

            DropdownMenuItem(
                text = { Text(stringResource(R.string.cast_menu)) },
                onClick = {
                    showVideoMenu = false

                    selectedDevice?.let { device ->

                        CastUtils.castUrl(device, url) { result ->

                            Log.d("CAST_MAIN", "castUrl CALLBACK FIRED → $result")

                            result?.let {
                                Log.d("CAST_MAIN", "SESSION OK → calling service")
                                castService?.attachSession(it, url)
                            }
                        }

                    } ?: run {
                        Toast.makeText(context, noDeviceSelectedText, Toast.LENGTH_SHORT).show()
                    }
                }
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.vlc_menu)) },
                onClick = {
                    showVideoMenu = false
                    openWithVlc(context, url)
                }
            )

            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_menu)) },
                onClick = {
                    showVideoMenu = false

                    val clipboard =
                        context.getSystemService(android.content.ClipboardManager::class.java)

                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(videoClipLabelText, url)
                    )
                }
            )
        }
    }

    // ─────────────────────────────
    // CAST DIALOG
    // ─────────────────────────────
    if (showCastDialog) {
        ModalBottomSheet(
            onDismissRequest = { showCastDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.cast_devices),
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.searching_devices))
                } else if (devices.isEmpty()) {
                    Text(stringResource(R.string.no_devices_found))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.chromecast_help),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn {
                        items(devices) { device ->
                            ListItem(
                                headlineContent = { Text(device.friendlyName) },
                                supportingContent = { Text(device.ip) },
                                leadingContent = {
                                    Icon(
                                        imageVector = Icons.Default.Cast,
                                        contentDescription = null,
                                        tint = if (selectedDevice == device) Color.Green else Color.Unspecified
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        selectedDevice = device
                                        connected = true
                                        showCastDialog = false
                                    }
                                    .padding(vertical = 4.dp)
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showCastDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }

}