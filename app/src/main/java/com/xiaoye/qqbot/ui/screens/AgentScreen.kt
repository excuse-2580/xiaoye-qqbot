package com.xiaoye.qqbot.ui.screens

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
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.xiaoye.qqbot.data.Agent
import com.xiaoye.qqbot.ui.OutlinedCardBox
import com.xiaoye.qqbot.ui.SectionTitle
import com.xiaoye.qqbot.vm.AppViewModel
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(vm: AppViewModel) {
    val agents by vm.agents.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val currentId by vm.currentAgentId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Agent?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionTitle("我的智能体") }

        items(agents, key = { it.id }) { a ->
            val srcName = sources.find { it.id == a.sourceId }?.name
            OutlinedCardBox(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(a.emoji, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            srcName ?: "未绑定模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (srcName == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (a.id == currentId) {
                            Text("使用中", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (a.id != currentId) {
                        TextButton(onClick = { vm.selectAgent(a.id) }) { Text("切换") }
                    }
                    IconButton(onClick = { editing = a }) {
                        Icon(Icons.Default.Edit, "编辑")
                    }
                    IconButton(onClick = { vm.removeAgent(a.id) }) {
                        Icon(Icons.Default.Delete, "删除")
                    }
                }
            }
        }

        item {
            FilledTonalButton(
                onClick = {
                    editing = Agent(
                        id = UUID.randomUUID().toString(),
                        name = "新智能体",
                        sourceId = sources.firstOrNull()?.id ?: "",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null)
                Text("  新建智能体", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    editing?.let { a ->
        EditAgentDialog(
            initial = a,
            vm = vm,
            onDismiss = { editing = null },
            onDone = {
                vm.upsertAgent(it)
                vm.selectAgent(it.id)
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditAgentDialog(
    initial: Agent,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onDone: (Agent) -> Unit,
) {
    var a by remember { mutableStateOf(initial) }
    val sources by vm.sources.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑智能体") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = a.emoji,
                        onValueChange = { a = a.copy(emoji = it.take(2)) },
                        label = { Text("头像") },
                        modifier = Modifier.weight(0.3f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = a.name,
                        onValueChange = { a = a.copy(name = it) },
                        label = { Text("名字") },
                        modifier = Modifier.weight(0.7f),
                        singleLine = true,
                    )
                }

                OutlinedTextField(
                    value = a.systemPrompt,
                    onValueChange = { a = a.copy(systemPrompt = it) },
                    label = { Text("人设 / System Prompt") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 3,
                    maxLines = 6,
                )

                OutlinedTextField(
                    value = a.greeting,
                    onValueChange = { a = a.copy(greeting = it) },
                    label = { Text("开场白") },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    singleLine = true,
                )

                Spacer(Modifier.padding(8.dp))
                Text("绑定模型源", style = MaterialTheme.typography.labelLarge)
                if (sources.isEmpty()) {
                    Text("还没有模型源，先去「模型」页加一个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                } else {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        sources.forEach { s ->
                            val picked = a.sourceId == s.id
                            AssistChip(
                                onClick = { a = a.copy(sourceId = s.id) },
                                label = { Text(s.name.ifBlank { "未命名" }) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (picked) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onDone(a) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
