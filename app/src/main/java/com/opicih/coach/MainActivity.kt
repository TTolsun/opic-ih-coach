package com.opicih.coach

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.opicih.coach.alarm.Notifications
import com.opicih.coach.ui.AppNav
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.theme.OpicTheme

class MainActivity : ComponentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        val container = (application as App).container
        setContent {
            OpicTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AppNav(pendingRoute.value) { pendingRoute.value = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let {
            pendingRoute.value = it
            Notifications.cancelMorning(this)
            intent.removeExtra(EXTRA_ROUTE)
        }
    }

    companion object { const val EXTRA_ROUTE = "route" }
}
