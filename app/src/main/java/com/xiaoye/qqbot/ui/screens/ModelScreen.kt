package com.xiaoye.qqbot.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoye.qqbot.data.DefaultModel
import com.xiaoye.qqbot.data.ModelKind
import com.xiaoye.qqbot.data.ModelSource
import com.xiaoye.qqbot.data.PresetProvider
import com.xiaoye.qqbot.ui.OutlinedCardBox
import com.xiaoye.qqbot.ui.SectionTitle
import com.xiaoye.qqbot.vm.AppViewModel
import com.xiaoye.qqbot.vm.EngineState
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(vm: AppViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val state by vm.engineState.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<ModelSource?>(null) }

    val pickGguf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val f = vm.importGguf(uri) ?: return@rememberLauncherForActivityResult
        val existing = sources.firstOrNull { it.kind == ModelKind.LOCAL_GGUF }
        val s = (existing ?: ModelSource(
            id = UUID.randomUUID().toString(),
            name = "手机本地 GGUF",
            kind = ModelKind.LOCAL_GGUF,
        )).copy(ggufPath = f.absolutePath, modelId = f.nameWithoutExtension)
        vm.upsertSource(s)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("手机本地模型（离线可用）") }
        item {
            LocalModelCard(
                vm = vm,
                state = state,
                path = sources.firstOrNull { it.kind == ModelKind.LOCAL_GGUF }?.ggufPath ?: "",
                onPick = { pickGguf.launch(arrayOf("*/*")) },
            )
        }

        item { Spacer(Modifier.padding(6.dp)) }
        item { SectionTitle("云端 / 局域网模型源") }

        items(sources.filter { it.kind != ModelKind.LOCAL_GGUF }, key = { it.id }) { s ->
            SourceRow(
                source = s,
                onEdit = { editing = s },
                onDelete = { vm.removeSource(s.id) },
            )
        }

        item {
            FilledTonalButton(
                onClick = { editing = ModelSource(id = UUID.randomUUID().toString(), name = "", kind = ModelKind.OPENAI_COMPAT) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Icon(Icons.Default.Add, null)
                Text("  添加模型源", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    editing?.let { s ->
        EditSourceDialog(
            initial = s,
            vm = vm,
            onDismiss = { editing = null },
            onDone = { vm.upsertSource(it); editing = null },
        )
    }
}

@Composable
private fun LocalModelCard(
    vm: AppViewModel,
    state: EngineState,
    path: String,
    onPick: () -> Unit,
) {
    OutlinedCardBox(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("llama.cpp 已打进 APK", style = MaterialTheme.typography.titleSmall)
            Text(
                "选一个 .gguf 文件，点加载就能离线推理，全程不用联网、不依赖 Termux。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                "推荐 ${DefaultModel.NAME}（${DefaultModel.SIZE_HINT}），中文好、手机上跑得动；"
                    + "内存吃紧就换 ${DefaultModel.SMALL_NAME}；3B 能跑但会明显慢。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (path.isNotBlank()) {
                Text(
                    "当前文件：${path.substringAfterLast('/')}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                OutlinedButton(onClick = onPick) { Text("选 .gguf") }
                Spacer(Modifier.padding(6.dp))
                when (state) {
                    is EngineState.Ready -> {
                        Button(onClick = { vm.unloadLocal() }) { Text("卸载") }
                        Spacer(Modifier.padding(6.dp))
                        Text(
                            "已就绪 · 上下文 ${state.nCtx}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    is EngineState.Loading -> Text(
                        "加载中…", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    is EngineState.Error -> Text(
                        "失败：${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    else -> Button(
                        onClick = {
                            vm.sources.value.firstOrNull { it.kind == ModelKind.LOCAL_GGUF }
                                ?.let { vm.loadLocal(it) }
                        },
                        enabled = path.isNotBlank(),
                    ) { Text("加载") }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: ModelSource, onEdit: () -> Unit, onDelete: () -> Unit) {
    OutlinedCardBox(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(source.name.ifBlank { "(未命名)" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    kindLabel(source.kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (source.baseUrl.isNotBlank()) {
                    Text(source.baseUrl, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (source.modelId.isNotBlank()) {
                    Text("模型：${source.modelId}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
        }
    }
}

private fun kindLabel(k: ModelKind): String = when (k) {
    ModelKind.LOCAL_GGUF -> "手机本地 · 离线"
    ModelKind.OPENAI_COMPAT -> "OpenAI 兼容"
    ModelKind.OLLAMA -> "Ollama"
    ModelKind.HUNYUAN -> "腾讯混元"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditSourceDialog(
    initial: ModelSource,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onDone: (ModelSource) -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "添加模型源" else "编辑模型源") },
        text = {
            Column {
                // 预设一键填
                Text("一键填写", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 6.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PresetProvider.entries.filter { it.kind != ModelKind.LOCAL_GGUF }.forEach { p ->
                        AssistChip(
                            onClick = {
                                s = s.copy(
                                    name = p.label,
                                    kind = p.kind,
                                    baseUrl = p.baseUrl,
                                    modelId = p.defaultModel,
                                )
                                probeResult = null
                            },
                            label = { Text(p.label) },
                        )
                    }
                }

                Spacer(Modifier.padding(8.dp))

                OutlinedTextField(
                    value = s.name,
                    onValueChange = { s = s.copy(name = it) },
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = s.baseUrl,
                    onValueChange = { s = s.copy(baseUrl = it) },
                    label = { Text("接口地址") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = s.apiKey,
                    onValueChange = { s = s.copy(apiKey = it) },
                    label = { Text("密钥 · ${keyHint(s.kind)}") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = s.modelId,
                    onValueChange = { s = s.copy(modelId = it) },
                    label = { Text("模型名") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    singleLine = true,
                )

                if (s.kind == ModelKind.HUNYUAN) {
                    OutlinedTextField(
                        value = s.region,
                        onValueChange = { s = s.copy(region = it) },
                        label = { Text("地域（Region）") },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        singleLine = true,
                    )
                    Text(
                        "密钥填 SecretId:SecretKey，中间一个英文冒号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                probeResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("❌")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        probing = true
                        probeResult = null
                    },
                    enabled = !probing && s.kind != ModelKind.LOCAL_GGUF,
                ) { Text("测试") }
                Button(onClick = { onDone(s) }) { Text("保存") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    // 测试连接（放在外面跑协程，避免 Dialog 重组时丢结果）
    if (probing) {
        androidx.compose.runtime.LaunchedEffect(s) {
            val r = vm.probe(s)
            probeResult = r.fold(
                onSuccess = { "✅ $it" },
                onFailure = { "❌ ${it.message ?: "连不上"}" },
            )
            probing = false
        }
    }
}

private fun keyHint(k: ModelKind): String = when (k) {
    ModelKind.HUNYUAN -> "SecretId:SecretKey"
    ModelKind.LOCAL_GGUF -> "不需要"
    else -> "sk-xxx，本地服务可留空"
}
