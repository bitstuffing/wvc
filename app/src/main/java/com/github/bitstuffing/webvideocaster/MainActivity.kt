package com.github.bitstuffing.webvideocaster

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.github.bitstuffing.webvideocaster.runtime.CastService
import com.github.bitstuffing.webvideocaster.ui.main.MainScreen
import com.github.bitstuffing.webvideocaster.ui.theme.WebVideoCasterTheme

class MainActivity : ComponentActivity() {

    var castServiceState = mutableStateOf<CastService?>(null)

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

        val intent = Intent(this, CastService::class.java)
        startService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)

        enableEdgeToEdge()

        setContent {
            WebVideoCasterTheme {
                MainScreen()
            }
        }
    }
}