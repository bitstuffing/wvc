
package com.github.bitstuffing.webvideocaster

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.bitstuffing.webvideocaster.runtime.CastService
import com.github.bitstuffing.webvideocaster.ui.theme.WebVideoCasterTheme
import com.github.bitstuffing.webvideocaster.utils.isMediaUrl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.github.bitstuffing.webvideocaster.utils.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    lateinit var webView: WebView
        private set

    lateinit var webContainer: FrameLayout
        private set

    var castServiceState = mutableStateOf<CastService?>(null)

    var detectedVideos by mutableStateOf(listOf<String>())
        private set

    var canGoBack by mutableStateOf(false)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    var currentUrl by mutableStateOf("https://www.atresplayer.com")
        private set

    private var urlInput by mutableStateOf("https://www.atresplayer.com")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            val binder = service as CastService.LocalBinder
            castServiceState.value = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            castServiceState.value = null
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, CastService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)


        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )

            insets
        }

        // Único WebView de la aplicación.
        webContainer = FrameLayout(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = false
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode =
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            settings.userAgentString = USER_AGENT

            setInitialScale(0)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false

            clearCache(true)
            clearHistory()

            webChromeClient = object: WebChromeClient(){
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                        in request.resources
                    ) {
                        request.grant(
                            arrayOf(
                                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                            )
                        )
                    } else {
                        request.deny()
                    }
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

                    val url = request?.url?.toString().orEmpty()

                    Log.d("CAST_MAIN", "shouldInterceptRequest → $url")

                    if (url.isNotBlank() && isMediaUrl(url)) {
                        runOnUiThread {
                            addVideo(url)

                            Toast.makeText(
                                this@MainActivity,
                                R.string.video_detected,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
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

        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Barra superior Compose: no crea otro navegador.
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )

            setContent {
                WebVideoCasterTheme {
                    val drawerState = rememberDrawerState(
                        initialValue = DrawerValue.Closed
                    )
                    val scope = rememberCoroutineScope()

                    var selectedVideo by remember {
                        mutableStateOf<String?>(null)
                    }
                    var showVideoMenu by remember {
                        mutableStateOf(false)
                    }
                    var showCastDialog by remember { mutableStateOf(false) }
                    var isSearching by remember { mutableStateOf(false) }
                    var devices by remember {
                        mutableStateOf<List<CastDevice>>(emptyList())
                    }
                    var selectedDevice by remember {
                        mutableStateOf<CastDevice?>(null)
                    }
                    var connected by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TopAppBar(
                            title = {
                                Text(stringResource(R.string.app_name))
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            if (drawerState.isClosed) {
                                                drawerState.open()
                                            } else {
                                                drawerState.close()
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.List,
                                        contentDescription = stringResource(R.string.videos)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        showCastDialog = true
                                        isSearching = true
                                        devices = emptyList()

                                        scope.launch {
                                            val lock = CastUtils.acquireMulticastLock(context)

                                            try {
                                                devices = CastUtils.searchDevices(context, 8000L)
                                            } catch (e: Exception) {
                                                Log.e("CAST_MAIN", "Error buscando dispositivos Cast", e)
                                                devices = emptyList()
                                            } finally {
                                                lock.release()
                                                isSearching = false
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (connected) {
                                            Icons.Filled.CastConnected
                                        } else {
                                            Icons.Filled.Cast
                                        },
                                        contentDescription = stringResource(R.string.cast),
                                        tint = if (connected) {
                                            Color(0xFF4CAF50)
                                        } else {
                                            LocalContentColor.current
                                        }
                                    )
                                }
                            }
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (webView.canGoBack()) webView.goBack()
                                    },
                                    enabled = canGoBack
                                ) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Atrás")
                                }

                                IconButton(
                                    onClick = {
                                        if (webView.canGoForward()) webView.goForward()
                                    },
                                    enabled = canGoForward
                                ) {
                                    Icon(Icons.Filled.ArrowForward, contentDescription = "Adelante")
                                }

                                IconButton(
                                    onClick = { webView.reload() }
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Recargar")
                                }

                                OutlinedTextField(
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 2.dp),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    placeholder = { Text("Dirección web") },
                                    keyboardOptions = KeyboardOptions(
                                        imeAction = ImeAction.Go
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = { navigateToUrl() }
                                    )
                                )

                                Button(
                                    onClick = { navigateToUrl() },
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Text("Ir")
                                }
                            }
                        }
                    }
                    if (drawerState.isOpen) {
                        ModalNavigationDrawer(
                            drawerState = drawerState,
                            gesturesEnabled = false,
                            drawerContent = {
                                ModalDrawerSheet {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = 12.dp,
                                                vertical = 8.dp
                                            ),
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
                                                contentDescription = stringResource(
                                                    R.string.clean_history
                                                )
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    drawerState.close()
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "Cerrar vídeos detectados"
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
                                                Box(
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
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

                                                    DropdownMenu(
                                                        expanded = showVideoMenu && selectedVideo == url,
                                                        onDismissRequest = {
                                                            showVideoMenu = false
                                                            selectedVideo = null
                                                        }
                                                    ) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(stringResource(R.string.cast_menu))
                                                            },
                                                            onClick = {
                                                                showVideoMenu = false

                                                                val service = castServiceState.value
                                                                val device = selectedDevice

                                                                when {
                                                                    device == null -> {
                                                                        Toast.makeText(
                                                                            this@MainActivity,
                                                                            R.string.no_device_selected,
                                                                            Toast.LENGTH_SHORT
                                                                        ).show()
                                                                    }

                                                                    service == null -> {
                                                                        Toast.makeText(
                                                                            this@MainActivity,
                                                                            "El servicio Cast no está disponible",
                                                                            Toast.LENGTH_SHORT
                                                                        ).show()
                                                                    }

                                                                    else -> {
                                                                        CastUtils.castUrl(device, url) { result ->
                                                                            Log.d(
                                                                                "CAST_MAIN",
                                                                                "castUrl CALLBACK FIRED → $result"
                                                                            )

                                                                            result?.let { castSession ->
                                                                                service.attachSession(
                                                                                    castSession,
                                                                                    url
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                }

                                                                selectedVideo = null
                                                            }
                                                        )

                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(stringResource(R.string.vlc_menu))
                                                            },
                                                            onClick = {
                                                                showVideoMenu = false
                                                                openWithVlc(context, url)
                                                                selectedVideo = null
                                                            }
                                                        )

                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(stringResource(R.string.copy_menu))
                                                            },
                                                            onClick = {
                                                                showVideoMenu = false

                                                                val clipboard =
                                                                    context.getSystemService(
                                                                        android.content.ClipboardManager::class.java
                                                                    )

                                                                clipboard.setPrimaryClip(
                                                                    android.content.ClipData.newPlainText(
                                                                        "videoClipLabelText",
                                                                        url
                                                                    )
                                                                )

                                                                selectedVideo = null
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                        }
                                    }
                                }
                            }
                        ) {
                            // No se crea ni se modifica ningún WebView aquí.
                        }
                    }

                    if (showCastDialog) {
                        ModalBottomSheet(
                            onDismissRequest = {
                                showCastDialog = false
                            }
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

                                Spacer(Modifier.height(12.dp))

                                if (isSearching) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(16.dp))

                                    Text(stringResource(R.string.searching_devices))
                                } else if (devices.isEmpty()) {
                                    Text(stringResource(R.string.no_devices_found))

                                    Spacer(Modifier.height(8.dp))

                                    Text(stringResource(R.string.chromecast_help))
                                } else {
                                    LazyColumn {
                                        items(devices) { device ->
                                            ListItem(
                                                headlineContent = {
                                                    Text(device.friendlyName)
                                                },
                                                supportingContent = {
                                                    Text(device.ip)
                                                },
                                                leadingContent = {
                                                    Icon(
                                                        Icons.Default.Cast,
                                                        contentDescription = null,
                                                        tint = if (selectedDevice == device) {
                                                            Color(0xFF4CAF50)
                                                        } else {
                                                            LocalContentColor.current
                                                        }
                                                    )
                                                },
                                                modifier = Modifier.clickable {
                                                    selectedDevice = device
                                                    connected = true
                                                    showCastDialog = false
                                                }
                                            )

                                            HorizontalDivider()
                                        }
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                TextButton(
                                    onClick = { showCastDialog = false }
                                ) {
                                    Text(stringResource(R.string.close))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Primero la barra y después el único WebView.
        root.addView(
            composeView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            webContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        webView.loadUrl(currentUrl)
    }

    private fun navigateToUrl() {
        val address = urlInput.trim()

        if (address.isBlank()) return

        val url = when {
            address.startsWith("https://", ignoreCase = true) -> address
            address.startsWith("http://", ignoreCase = true) -> address
            else -> "https://$address"
        }

        currentUrl = url
        webView.loadUrl(url)
    }

    private fun addVideo(url: String) {
        if (url.isBlank()) return
        if (detectedVideos.contains(url)) return

        detectedVideos = listOf(url) + detectedVideos
        Log.d("CAST_MAIN", "Video añadido: $url")
    }

    override fun onDestroy() {
        if (isFinishing) {
            webView.stopLoading()
            webView.destroy()
        }

        try {
            unbindService(connection)
        } catch (_: IllegalArgumentException) {
            Log.e("CAST_MAIN", "Error al desregistrar el servicio Cast")
        }

        super.onDestroy()
    }
}