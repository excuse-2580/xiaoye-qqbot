package com.xiaoye.qqbot.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoye.qqbot.data.ChatMessage
import com.xiaoye.qqbot.vm.AppViewModel
import com.xiaoye.qqbot.vm.EngineState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val agents by vm.agents.collectAsStateWithLifecycle()
    val currentId by vm.currentAgentId.collectAsStateWithLifecycle()
    val agent = agents.find { it.id == currentId }
    val state by vm.engineState.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 有新内容就滚到底
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部状态条
        Surface(tonalElevation = 2.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "${agent?.emoji ?: "🐺"} ${agent?.name ?: "未选择智能体"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    statusText(state, vm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(agent?.emoji ?: "🐺", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.padding(6.dp))
                    Text(
                        agent?.greeting ?: "还没有智能体，去「智能体」页建一个",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(m)
                }
            }
        }

        // 输入区
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 160.dp),
                placeholder = { Text("说点什么…") },
                shape = RoundedCornerShape(20.dp),
                maxLines = 5,
            )
            Spacer(Modifier.padding(4.dp))
            if (generating) {
                IconButton(onClick = { vm.stop() }, modifier = Modifier.padding(bottom = 6.dp)) {
                    Icon(Icons.Default.Stop, "停止")
                }
            } else {
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Icon(Icons.Default.Send, "发送")
                }
            }
        }
    }
}

@Composable
private fun statusText(state: EngineState, vm: AppViewModel): String {
    val src = vm.sourceOf()
    val name = src?.name ?: "未绑定模型"
    return when (state) {
        is EngineState.Idle -> "$name · 未加载本地模型"
        is EngineState.Loading -> "正在加载模型…（首次可能要几十秒）"
        is EngineState.Ready -> "$name · 本地模型就绪（上下文 ${state.nCtx}）"
        is EngineState.Error -> "模型出错：${state.message}"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: ChatMessage) {
    val isUser = m.role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            color = bg,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                RichText(m.content, fg)
            }
        }
    }
}

/**
 * 极简 Markdown：只处理 ``` 代码块，其余原样输出。
 * 不引第三方库，够看就行。
 */
@Composable
private fun RichText(text: String, color: androidx.compose.ui.graphics.Color) {
    val parts = remember(text) { splitCodeBlocks(text) }
    Column {
        parts.forEach { part ->
            if (part.isCode) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Text(
                        part.text.trim('\n'),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            } else {
                Text(
                    part.text.trim('\n'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }
        }
    }
}

private data class Piece(val text: String, val isCode: Boolean)

private fun splitCodeBlocks(text: String): List<Piece> {
    val out = ArrayList<Piece>()
    val re = Regex("```(?:[a-zA-Z0-9]*)\n?")
    var last = 0
    var inCode = false
    re.findAll(text).forEach { mr ->
        val seg = text.substring(last, mr.range.first)
        if (seg.isNotEmpty()) out.add(Piece(seg, inCode))
        last = mr.range.last + 1
        inCode = !inCode
    }
    if (last < text.length) out.add(Piece(text.substring(last), inCode))
    return out.ifEmpty { listOf(Piece(text, false)) }
}
