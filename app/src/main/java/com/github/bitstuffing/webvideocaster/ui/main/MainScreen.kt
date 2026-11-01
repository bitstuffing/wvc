package com.github.bitstuffing.webvideocaster.ui.main

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.bitstuffing.webvideocaster.R
import com.github.bitstuffing.webvideocaster.chromecast.runtime.CastService
import com.github.bitstuffing.webvideocaster.chromecast.utils.CastDevice
import com.github.bitstuffing.webvideocaster.chromecast.utils.CastUtils
import com.github.bitstuffing.webvideocaster.chromecast.utils.openWithVlc
import kotlinx.coroutines.launch

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    webView: WebView,
    detectedVideos: List<String>,
    canGoBack: Boolean,
    canGoForward: Boolean,
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    onNavigate: () -> Unit,
    onClearVideos: () -> Unit,
    castServiceState: State<CastService?>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showVideos by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<CastDevice?>(null) }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text(stringResource(R.string.app_name)) },
            actions = {
                IconButton(onClick = { showVideos = true }) {
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
                                devices = CastUtils.searchDevices(context, 4000L)
                            } catch (e: Exception) {
                                Log.e("CAST_MAIN","Error seeking Cast device",e)
                                devices = emptyList()
                            } finally {
                                lock.release()
                                isSearching = false
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (selectedDevice != null) {
                            Icons.Filled.CastConnected
                        } else {
                            Icons.Filled.Cast
                        },
                        contentDescription = stringResource(R.string.cast),
                        tint = if (selectedDevice != null) {
                            Color(0xFF4CAF50)
                        } else {
                            LocalContentColor.current
                        }
                    )
                }
            }
        )

        BrowserBar(
            webView = webView,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            urlInput = urlInput,
            onUrlInputChange = onUrlInputChange,
            onNavigate = onNavigate
        )
    }

    if (showVideos) {
        VideoDrawer(
            videos = detectedVideos,
            onDismiss = { showVideos = false },
            onClear = onClearVideos,
            onCast = { url ->
                val service = castServiceState.value
                val device = selectedDevice

                when {
                    device == null -> Toast.makeText(context,R.string.no_device_selected,Toast.LENGTH_SHORT).show()
                    service == null -> Toast.makeText(context,R.string.cast_service_unavailable,Toast.LENGTH_SHORT).show()
                    else -> CastUtils.castUrl(device, url) { result ->
                        scope.launch {
                            if (result == null) {
                                Toast.makeText(context, R.string.cast_service_unavailable, Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            Log.d("CAST_MAIN", "Cast session ready")
                            service.attachSession(result, url)
                        }
                    }
                }
            },
            onOpenVlc = { url -> openWithVlc(context, url) },
            onCopy = { url ->
                val clipboard = context.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager

                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.video_clip_label), url))
            }
        )
    }

    if (showCastDialog) {
        CastDevicesSheet(
            isSearching = isSearching,
            devices = devices,
            selectedDevice = selectedDevice,
            onSelectDevice = { device ->
                selectedDevice = device
                showCastDialog = false
                val service = castServiceState.value
                if (service != null) {
                    service.monitorDevice(device)
                } else {
                    Toast.makeText(context, R.string.cast_service_unavailable, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showCastDialog = false }
        )
    }
}