package com.github.bitstuffing.webvideocaster.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.bitstuffing.webvideocaster.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDrawer(
    videos: List<String>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onCast: (String) -> Unit,
    onOpenVlc: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    var selectedVideo by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth(),horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                Text(text = stringResource(R.string.videos_detected),style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Delete,contentDescription = stringResource(R.string.clean_history))
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_detected_videos))
                }
            }

            if (videos.isEmpty()) {
                Text(stringResource(R.string.no_videos_yet), modifier = Modifier.padding(12.dp))
            } else {
                LazyColumn {
                    items(videos, key = { it }) { url ->
                        Box(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = {
                                    Text(url, maxLines = 1)
                                },
                                modifier = Modifier.clickable {
                                    selectedVideo = url
                                }
                            )

                            DropdownMenu(
                                expanded = selectedVideo == url,
                                onDismissRequest = {
                                    selectedVideo = null
                                }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.cast_menu))
                                    },
                                    onClick = {
                                        selectedVideo = null
                                        onCast(url)
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.vlc_menu))
                                    },
                                    onClick = {
                                        selectedVideo = null
                                        onOpenVlc(url)
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.copy_menu))
                                    },
                                    onClick = {
                                        selectedVideo = null
                                        onCopy(url)
                                    }
                                )
                            }
                        }

                        HorizontalDivider()
                    }
                }
            }
        }
    }
}