package com.refacecslive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.refacecslive.ui.RefaceCSLiveApp
import com.refacecslive.ui.theme.RefaceCSLiveTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RefaceCSLiveTheme {
                RefaceCSLiveApp(viewModel)
            }
        }
    }
}
