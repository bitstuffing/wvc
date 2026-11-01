package com.github.bitstuffing.webvideocaster.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.bitstuffing.webvideocaster.R
import com.github.bitstuffing.webvideocaster.chromecast.utils.CastDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDevicesSheet(
    isSearching: Boolean,
    devices: List<CastDevice>,
    selectedDevice: CastDevice?,
    onSelectDevice: (CastDevice) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = stringResource(R.string.cast_devices), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            when {
                isSearching -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.searching_devices))
                }
                devices.isEmpty() -> {
                    Text(stringResource(R.string.no_devices_found))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.chromecast_help))
                }
                else -> {
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
                                    onSelectDevice(device)
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    }
}