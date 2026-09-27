package com.xiaoye.qqbot.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoye.qqbot.data.QQBotConfig
import com.xiaoye.qqbot.data.QQMode
import com.xiaoye.qqbot.data.TriggerMode
import com.xiaoye.qqbot.qq.BotService
import com.xiaoye.qqbot.ui.OutlinedCardBox
import com.xiaoye.qqbot.ui.SectionTitle
import com.xiaoye.qqbot.vm.AppViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BotScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val cfg by vm.qqConfig.collectAsStateWithLifecycle()
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝也不影响启动，只是通知不显示 */ }
    var running by remember { mutableStateOf(BotService.running) }
    var status by remember { mutableStateOf(BotService.statusText) }

    // 轮询服务状态（前台服务没用绑定，简单轮询够用）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            running = BotService.running
            status = BotService.statusText
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        /* ---------- 运行状态 ---------- */
        item { SectionTitle("运行状态") }
        item {
            OutlinedCardBox(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (running) "运行中" else "未运行",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (running) {
                            Button(onClick = {
                                context.startService(
                                    Intent(context, BotService::class.java).setAction(BotService.ACTION_STOP)
                                )
                                running = false
                            }) {
                                Icon(Icons.Default.Stop, null)
                                Text("  停止", modifier = Modifier.padding(start = 4.dp))
                            }
                        } else {
                            Button(onClick = {
                                // Android 13+ 要先有通知权限，前台服务的通知才弹得出来
                                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                                context.startForegroundService(
                                    Intent(context, BotService::class.java).setAction(BotService.ACTION_START)
                                )
                                running = true
                            }, enabled = cfg.mode != QQMode.OFF) {
                                Icon(Icons.Default.PlayArrow, null)
                                Text("  启动", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
        }

        /* ---------- 接入模式 ---------- */
        item { SectionTitle("接入方式") }
        item {
            OutlinedCardBox(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        QQMode.entries.forEach { m ->
                            FilterChip(
                                selected = cfg.mode == m,
                                onClick = { vm.saveQQ(cfg.copy(mode = m)) },
                                label = { Text(modeLabel(m)) },
                            )
                        }
                    }
                    Text(
                        modeHint(cfg.mode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        /* ---------- 各模式的参数 ---------- */
        when (cfg.mode) {
            QQMode.OFFICIAL -> {
                item { SectionTitle("QQ 官方 API（q.qq.com）") }
                item {
                    OutlinedCardBox(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Field("AppID", cfg.appId) { vm.saveQQ(cfg.copy(appId = it)) }
                            Field(
                                "机器人密钥 Secret", cfg.officialToken,
                                password = true
                            ) { vm.saveQQ(cfg.copy(officialToken = it)) }
                            Text(
                                "到 q.qq.com 创建机器人，审核通过后在「开发设置」里拿 AppID 和密钥。"
                                    + "密钥用 Android Keystore 加密存储，不落明文。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            QQMode.ONEBOT_WS -> {
                item { SectionTitle("OneBot 反向 WS（推荐）") }
                item {
                    OutlinedCardBox(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Field("反向 WS 地址", cfg.wsUrl) { vm.saveQQ(cfg.copy(wsUrl = it)) }
                            Field("Access Token（可留空）", cfg.accessToken, password = true) {
                                vm.saveQQ(cfg.copy(accessToken = it))
                            }
                            Text(
                                "在协议端（Lagrange / LLOneBot / NapCat 等）开启「反向 WebSocket」，"
                                    + "地址填 ws://电脑IP:端口。手机主动连出去，不用暴露端口、不用内网穿透。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            QQMode.ONEBOT_HTTP -> {
                item { SectionTitle("OneBot HTTP 上报") }
                item {
                    OutlinedCardBox(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Field(
                                "本机监听端口", cfg.httpPort.toString(),
                                numeric = true
                            ) { vm.saveQQ(cfg.copy(httpPort = it.toIntOrNull() ?: 5700)) }
                            Field("协议端 API 地址", cfg.onebotApiBase) {
                                vm.saveQQ(cfg.copy(onebotApiBase = it))
                            }
                            Field("Access Token（签名用）", cfg.accessToken, password = true) {
                                vm.saveQQ(cfg.copy(accessToken = it))
                            }
                            Text(
                                "上报只负责收消息，发消息要反过来调协议端的 HTTP API，所以两个地址都要填。"
                                    + "上报会做 HMAC-SHA1 签名校验，填了 token 就必须对得上。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            QQMode.OFF -> {}
        }

        /* ---------- 触发规则 ---------- */
        item { SectionTitle("触发规则") }
        item {
            OutlinedCardBox(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TriggerMode.entries.forEach { t ->
                            FilterChip(
                                selected = cfg.trigger == t,
                                onClick = { vm.saveQQ(cfg.copy(trigger = t)) },
                                label = { Text(triggerLabel(t)) },
                            )
                        }
                    }

                    if (cfg.trigger == TriggerMode.PREFIX) {
                        Field(
                            "前缀（每行一个）", cfg.prefixes, multiline = true
                        ) { vm.saveQQ(cfg.copy(prefixes = it)) }
                    }
                    if (cfg.trigger == TriggerMode.KEYWORD) {
                        Field(
                            "关键词（每行一个，留空=全回）", cfg.keywords, multiline = true
                        ) { vm.saveQQ(cfg.copy(keywords = it)) }
                    }

                    Field(
                        "群白名单（每行一个群号，留空=全部放行）",
                        cfg.groupWhitelist, multiline = true
                    ) { vm.saveQQ(cfg.copy(groupWhitelist = it)) }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("回复私聊", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = cfg.replyPrivate,
                            onCheckedChange = { vm.saveQQ(cfg.copy(replyPrivate = it)) },
                        )
                    }
                }
            }
        }

        /* ---------- 限流与分段 ---------- */
        item { SectionTitle("限流与发送") }
        item {
            OutlinedCardBox(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    SliderItem("消息去重窗口", cfg.dedupWindowSec.toFloat(), 1f..60f,
                        "${cfg.dedupWindowSec} 秒") {
                        vm.saveQQ(cfg.copy(dedupWindowSec = it.toInt()))
                    }
                    SliderItem("回复冷却", cfg.cooldownSec.toFloat(), 0f..60f,
                        "${cfg.cooldownSec} 秒") {
                        vm.saveQQ(cfg.copy(cooldownSec = it.toInt()))
                    }
                    SliderItem("分段字数", cfg.segmentChars.toFloat(), 100f..1000f,
                        "${cfg.segmentChars} 字") {
                        vm.saveQQ(cfg.copy(segmentChars = it.toInt()))
                    }
                    SliderItem("段间间隔", cfg.segmentDelayMs.toFloat(), 0f..3000f,
                        "${(cfg.segmentDelayMs / 100) * 100} ms") {
                        vm.saveQQ(cfg.copy(segmentDelayMs = (it / 100).toLong() * 100))
                    }
                    SliderItem("最大并发", cfg.maxQueue.toFloat(), 1f..16f,
                        "${cfg.maxQueue} 条") {
                        vm.saveQQ(cfg.copy(maxQueue = it.toInt()))
                    }
                    Text(
                        "去重防协议端重复上报，冷却防刷屏，分段让长回复像真人一句句发。"
                            + "参数调温和一点，能明显降低被风控的概率。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        /* ---------- 风险提示 ---------- */
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("⚠️ 风险提示", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "OneBot 属于第三方协议，腾讯并未授权，使用存在封号风险。"
                            + "强烈建议用小号测试，不要用常用大号。"
                            + "长期稳定运行请走 QQ 官方 API（q.qq.com 创建机器人）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    password: Boolean = false,
    multiline: Boolean = false,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 6 else 1,
        visualTransformation = if (password) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun SliderItem(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private fun modeLabel(m: QQMode): String = when (m) {
    QQMode.OFF -> "关闭"
    QQMode.OFFICIAL -> "官方 API"
    QQMode.ONEBOT_WS -> "OneBot 反向WS"
    QQMode.ONEBOT_HTTP -> "OneBot HTTP上报"
}

private fun modeHint(m: QQMode): String = when (m) {
    QQMode.OFF -> "当前不接入 QQ，只在 App 里本地对话。"
    QQMode.OFFICIAL -> "q.qq.com 官方机器人，合规稳定，适合长期运行；需要审核。"
    QQMode.ONEBOT_WS -> "推荐。手机主动连协议端，不用暴露端口，配置最简单。"
    QQMode.ONEBOT_HTTP -> "协议端把消息 POST 到手机，需要手机和协议端在同一局域网。"
}

private fun triggerLabel(t: TriggerMode): String = when (t) {
    TriggerMode.AT_ONLY -> "仅 @机器人"
    TriggerMode.PREFIX -> "前缀触发"
    TriggerMode.KEYWORD -> "关键词"
    TriggerMode.ALL_GROUP -> "群里全回"
}
