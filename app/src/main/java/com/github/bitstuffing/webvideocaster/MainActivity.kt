package com.github.bitstuffing.webvideocaster

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.bitstuffing.webvideocaster.runtime.CastService
import com.github.bitstuffing.webvideocaster.ui.main.MainScreen
import com.github.bitstuffing.webvideocaster.ui.theme.WebVideoCasterTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //startService(Intent(this,CastService::class.java))

        enableEdgeToEdge()

        setContent {
            WebVideoCasterTheme {
                MainScreen()
            }
        }
    }
}