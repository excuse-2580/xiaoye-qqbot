package com.xiaoye.qqbot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoye.qqbot.engine.InferParams
import com.xiaoye.qqbot.engine.LlmEngine
import com.xiaoye.qqbot.ui.OutlinedCardBox
import com.xiaoye.qqbot.ui.SectionTitle
import com.xiaoye.qqbot.vm.AppViewModel

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    dynamicColor: Boolean,
    darkMode: Int,
    onThemeChange: (Boolean, Int) -> Unit,
) {
    val agents by vm.agents.collectAsStateWithLifecycle()
    val currentId by vm.currentAgentId.collectAsStateWithLifecycle()
    val agent = agents.find { it.id == currentId }
    val params = agent?.params ?: InferParams()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("外观") }
        item {
            OutlinedCardBox(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("跟随壁纸取色", style = MaterialTheme.typography.bodyLarge)
                            Text("Android 12+ 生效", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = { onThemeChange(it, darkMode) },
                        )
                    }

                    Spacer(Modifier.padding(8.dp))
                    Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, v) ->
                            FilterChip(
                                selected = darkMode == v,
                                onClick = { onThemeChange(dynamicColor, v) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.padding(4.dp)) }
        item { SectionTitle("本地推理参数（${agent?.name ?: "无"}）") }
        item {
            OutlinedCardBox(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "只对手机本地的 .gguf 生效。云端模型用的是各自服务端的默认采样。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(6.dp))

                    ParamSlider(
                        label = "上下文长度",
                        value = params.nCtx.toFloat(),
                        range = 512f..8192f,
                        steps = 14,
                        format = { "${it.toInt()}" },
                        onChange = { vm.updateParams(params.copy(nCtx = it.toInt())) },
                    )
                    Text(
                        "越大越吃内存。改了要重新加载模型才生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.padding(8.dp))
                    ParamSlider(
                        label = "线程数（本机 ${LlmEngine.recommendedThreads()} 核推荐）",
                        value = params.nThreads.toFloat(),
                        range = 1f..8f,
                        steps = 6,
                        format = { "${it.toInt()} 线程" },
                        onChange = { vm.updateParams(params.copy(nThreads = it.toInt())) },
                    )

                    Spacer(Modifier.padding(8.dp))
                    ParamSlider(
                        label = "Temperature 创造力",
                        value = params.temp,
                        range = 0f..2f,
                        steps = 20,
                        format = { "%.2f".format(it) },
                        onChange = { vm.updateParams(params.copy(temp = it)) },
                    )

                    Spacer(Modifier.padding(8.dp))
                    ParamSlider(
                        label = "Top-P 核采样",
                        value = params.topP,
                        range = 0.1f..1f,
                        steps = 17,
                        format = { "%.2f".format(it) },
                        onChange = { vm.updateParams(params.copy(topP = it)) },
                    )

                    Spacer(Modifier.padding(8.dp))
                    ParamSlider(
                        label = "Top-K（0 = 不限制）",
                        value = params.topK.toFloat(),
                        range = 0f..100f,
                        steps = 19,
                        format = { "${it.toInt()}" },
                        onChange = { vm.updateParams(params.copy(topK = it.toInt())) },
                    )

                    Spacer(Modifier.padding(8.dp))
                    ParamSlider(
                        label = "重复惩罚",
                        value = params.repeatPenalty,
                        range = 1f..2f,
                        steps = 10,
                        format = { "%.2f".format(it) },
                        onChange = { vm.updateParams(params.copy(repeatPenalty = it)) },
                    )

                    Spacer(Modifier.padding(8.dp))
                    ParamSlider(
                        label = "单条最多生成",
                        value = params.maxTokens.toFloat(),
                        range = 64f..2048f,
                        steps = 30,
                        format = { "${it.toInt()} token" },
                        onChange = { vm.updateParams(params.copy(maxTokens = it.toInt())) },
                    )
                }
            }
        }

        item { Spacer(Modifier.padding(4.dp)) }
        item { SectionTitle("关于") }
        item {
            OutlinedCardBox(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("AI Agent Studio", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Kotlin + Jetpack Compose + Material Design 3，"
                            + "llama.cpp 通过 JNI 编进 libggml-jni.so，手机本地跑 GGUF 不需要联网、不依赖 Termux。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "小提示：连续推理几分钟手机会发热降频，速度变慢是正常的；"
                            + "想要长对话就把上下文调小一点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
