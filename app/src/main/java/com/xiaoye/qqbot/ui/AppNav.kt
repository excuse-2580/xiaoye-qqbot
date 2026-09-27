package com.xiaoye.qqbot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoye.qqbot.ui.screens.AgentScreen
import com.xiaoye.qqbot.ui.screens.ChatScreen
import com.xiaoye.qqbot.ui.screens.ModelScreen
import com.xiaoye.qqbot.ui.screens.SettingsScreen
import com.xiaoye.qqbot.vm.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    vm: AppViewModel,
    dynamicColor: Boolean,
    darkMode: Int,
    onThemeChange: (Boolean, Int) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.toast.collect { snackbar.showSnackbar(it) }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }

    val tabs = listOf(
        "对话" to Icons.Default.Chat,
        "智能体" to Icons.Default.Person,
        "模型" to Icons.Default.Storage,
        "机器人" to Icons.Default.SmartToy,
        "设置" to Icons.Default.Settings,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ChatScreen(vm)
                1 -> AgentScreen(vm)
                2 -> ModelScreen(vm)
                3 -> BotScreen(vm)
                4 -> SettingsScreen(vm, dynamicColor, darkMode, onThemeChange)
            }
        }
    }
}

/** 通用的分区标题 */
@Composable
fun SectionTitle(text: String, icon: ImageVector? = null) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

/** 轻量卡片 */
@Composable
fun OutlinedCardBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        content = content,
    )
}
