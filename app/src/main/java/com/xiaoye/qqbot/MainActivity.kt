package com.xiaoye.qqbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoye.qqbot.data.Prefs
import com.xiaoye.qqbot.ui.AppRoot
import com.xiaoye.qqbot.ui.theme.AasTheme
import com.xiaoye.qqbot.vm.AppViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val prefs = remember(context) { Prefs(context) }

            var dynamic by remember { mutableStateOf(prefs.dynamicColor) }
            var darkMode by remember { mutableIntStateOf(prefs.darkMode) }

            val vm: AppViewModel = viewModel()

            AasTheme(
                darkTheme = when (darkMode) {
                    1 -> false
                    2 -> true
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                },
                dynamicColor = dynamic,
            ) {
                AppRoot(
                    vm = vm,
                    dynamicColor = dynamic,
                    darkMode = darkMode,
                    onThemeChange = { d, m ->
                        prefs.dynamicColor = d
                        prefs.darkMode = m
                        dynamic = d
                        darkMode = m
                    },
                )
            }
        }
    }
}
