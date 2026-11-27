package com.amz.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Bg = Color(0xFF090A0D)
private val Panel = Color(0xFF15171D)
private val Surface2 = Color(0xFF20232B)
private val Accent = Color(0xFFB7F36B)
private val Muted = Color(0xFF9297A3)
private val White = Color(0xFFF4F5F7)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AMZTheme { AMZApp() } }
    }
}

@Composable
private fun AMZTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Accent,
        onPrimary = Bg, onBackground = White, onSurface = White,
        secondary = Muted
    ), content = content)
}

private data class ChatMessage(val text: String, val user: Boolean)

@Composable
private fun AMZApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { ModelRepository(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Chat") }
    var mode by remember { mutableStateOf("Fast") }
    var draft by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<HuggingFaceModel>>(emptyList()) }
    var installed by remember { mutableStateOf(repo.installedModels()) }
    var selectedModel by remember { mutableStateOf<File?>(null) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    val inference = remember { InferenceRepository() }
    var modelLoaded by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var contextSize by remember { mutableIntStateOf(2048) }
    val messages = remember { mutableStateListOf(ChatMessage("سلام! برای اجرای آفلاین، ابتدا یک مدل GGUF وارد یا دانلود کن.", false)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            status = "در حال وارد کردن مدل…"
            runCatching { withContext(Dispatchers.IO) { repo.importGguf(uri) } }
                .onSuccess { installed = repo.installedModels(); selectedModel = it; status = "مدل وارد شد: ${it.name}" }
                .onFailure { status = it.message ?: "خطا در وارد کردن مدل" }
            loading = false
        }
    }
    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Panel, contentColor = White) {
            listOf("Chat" to Icons.Default.ChatBubble, "Models" to Icons.Default.Memory, "Pals" to Icons.Default.Face, "Settings" to Icons.Default.Settings).forEach { (name, icon) ->
                NavigationBarItem(selected = tab == name, onClick = { tab = name }, icon = { Icon(icon, null) }, label = { Text(name) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Surface2, unselectedIconColor = Muted, unselectedTextColor = Muted))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AMZ AI", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = White)
                    Text(if (tab == "Chat") "Your private AI space" else tab, color = Muted, fontSize = 12.sp)
                }
                Surface(color = Surface2, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(Accent, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text("LOCAL FIRST", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            when (tab) {
                "Chat" -> {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChip("Fast", mode == "Fast") { mode = "Fast" }
                        ModeChip("Think", mode == "Think") { mode = "Think" }
                        Spacer(Modifier.weight(1f))
                        Text(if (modelLoaded) "● " + (selectedModel?.name ?: "Loaded") else selectedModel?.name ?: "No model loaded", color = if (modelLoaded) Accent else Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
                        items(messages) { msg ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.user) Arrangement.End else Arrangement.Start) {
                                Surface(color = if (msg.user) Color(0xFF28321F) else Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 310.dp)) {
                                    Text(msg.text, color = White, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(15.dp))
                                }
                            }
                        }
                    }
                    Surface(color = Panel, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            BasicTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth().padding(8.dp), textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent), decorationBox = { inner -> if (draft.isEmpty()) Text("Message AMZ AI…", color = Muted); inner() })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { tab = "Models" }) { Icon(Icons.Default.Add, null, tint = Muted) }
                                Text("Model & tools", color = Muted, fontSize = 11.sp)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = {
                                    val text = draft.trim()
                                    if (text.isNotEmpty() && !generating) {
                                        messages.add(ChatMessage(text, true)); draft = ""
                                        if (!modelLoaded) {
                                            messages.add(ChatMessage("ابتدا یک مدل را از بخش Models بارگذاری کن.", false))
                                        } else {
                                            generating = true
                                            scope.launch {
                                                val started = System.currentTimeMillis()
                                                val prompt = if (mode == "Think") "Think carefully and reason through the task before answering.\\n\\nUser: $text\\nAssistant:" else "User: $text\\nAssistant:"
                                                messages.add(ChatMessage("", false))
                                                val answerIndex = messages.lastIndex
                                                var outputTokens = 0
                                                val result = runCatching {
                                                    inference.generate(prompt, 256) { chunk, generatedTokenCount ->
                                                        outputTokens = generatedTokenCount
                                                        val current = messages.getOrNull(answerIndex)
                                                        if (current != null) messages[answerIndex] = current.copy(text = current.text + chunk)
                                                    }
                                                }
                                                val seconds = (System.currentTimeMillis() - started) / 1000.0
                                                result.onSuccess { answer ->
                                                    val current = messages.getOrNull(answerIndex)
                                                    if (current != null && answer.startsWith("ERROR:")) messages[answerIndex] = current.copy(text = answer)
                                                    else if (current != null) messages[answerIndex] = current.copy(text = current.text + "\\n\\n" + outputTokens + " output tokens · " + (if (seconds > 0) "%.1f".format(outputTokens / seconds) + " tok/s · " else "") + "%.1f".format(seconds) + "s")
                                                }.onFailure { err ->
                                                    val current = messages.getOrNull(answerIndex)
                                                    if (current != null) messages[answerIndex] = current.copy(text = "خطای اجرای مدل: " + (err.message ?: "unknown"))
                                                }
                                                generating = false
                                            }
                                        }
                                    }
                                }, enabled = draft.isNotBlank() && !generating) { Icon(Icons.Default.Send, null, tint = if (draft.isNotBlank()) Accent else Muted) }
                            }
                        }
                    }
                }
                "Models" -> {
                    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        Text("Model library", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("مدل‌های GGUF را جست‌وجو، دانلود یا از حافظه وارد کن.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { picker.launch(arrayOf("*/*")) }, colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = White), shape = CircleShape) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Import GGUF") }
                            OutlinedButton(onClick = { installed = repo.installedModels() }, shape = CircleShape) { Text("Refresh") }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicTextField(query, { query = it }, Modifier.weight(1f).background(Panel, RoundedCornerShape(14.dp)).padding(14.dp), textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent), decorationBox = { inner -> if (query.isBlank()) Text("Search Hugging Face GGUF…", color = Muted); inner() })
                            IconButton(onClick = {
                                if (query.isNotBlank() && !loading) scope.launch {
                                    loading = true; status = "Searching Hugging Face…"
                                    runCatching { repo.search(query) }.onSuccess { models = it; status = "Found ${it.size} GGUF repositories" }.onFailure { status = it.message ?: "Search failed" }
                                    loading = false
                                }
                            }) { Icon(Icons.Default.Search, null, tint = Accent) }
                        }
                        if (loading || generating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Accent)
                        if (status.isNotBlank()) Text(status, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (installed.isNotEmpty()) {
                                item { Text("Downloaded models", color = Accent, fontWeight = FontWeight.SemiBold) }
                                items(installed) { file ->
                                    ModelCard(file.name, "Local · ${"%.2f".format(file.length() / 1_073_741_824.0)} GB", file == selectedModel, if (modelLoaded && file == selectedModel) "Loaded" else "Load") {
                                        selectedModel = file
                                        scope.launch {
                                            loading = true
                                            status = "Loading model into native runtime…"
                                            val result = runCatching { inference.load(file.absolutePath, contextSize) }
                                            result.onSuccess { message ->
                                                modelLoaded = message == "OK"
                                                status = if (modelLoaded) "Model loaded · CPU backend" else message
                                            }.onFailure { modelLoaded = false; status = it.message ?: "Native model load failed" }
                                            loading = false
                                        }
                                    }
                                }
                            }
                            if (models.isNotEmpty()) item { Text("Hugging Face results", color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp)) }
                            items(models) { model ->
                                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
                                    Text(model.id, color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("${model.downloads} downloads · ${model.likes} likes · ${model.ggufFiles.size} GGUF files", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 6.dp))
                                    model.ggufFiles.take(4).forEach { file ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(file, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                            TextButton(onClick = {
                                                if (!loading) scope.launch {
                                                    loading = true; progress = null; status = "Downloading ${file}…"
                                                    runCatching { repo.download(model.id, file) { progress = it } }
                                                        .onSuccess { installed = repo.installedModels(); status = "Downloaded ${it.name}" }
                                                        .onFailure { status = it.message ?: "Download failed" }
                                                    loading = false
                                                }
                                            }) { Text("Download", color = Accent, fontSize = 11.sp) }
                                        }
                                    }
                                    if (model.ggufFiles.size > 4) Text("+ ${model.ggufFiles.size - 4} more files", color = Muted, fontSize = 10.sp)
                                }
                            }
                        }
                        progress?.let { p ->
                            Text("${p.fileName} · ${p.percent}% · ${p.received / 1_048_576} MB", color = Accent, fontSize = 11.sp)
                            LinearProgressIndicator({ p.percent / 100f }, Modifier.fillMaxWidth().padding(bottom = 10.dp), color = Accent)
                        }
                    }
                }
                "Pals" -> FeaturePage("Your Pals", "دستیارهای شخصی با شخصیت، System Prompt و تنظیمات مستقل.", listOf("Create a Pal", "Assistant presets", "Roleplay profiles"))
                else -> FeaturePage("Settings", "تنظیمات پیشرفته در حال آماده‌سازی است.", listOf("Inference parameters", "Context & memory", "Voice input / output", "Connected servers", "Appearance & language", "Export chats"))
            }
        }
    }
}

@Composable
private fun ModelCard(title: String, subtitle: String, selected: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = White, fontSize = 12.sp); Text(subtitle, color = Muted, fontSize = 10.sp) }
        TextButton(onClick = onClick) { Text(if (selected) "Selected" else action, color = Accent) }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (selected) Accent else Surface2, shape = CircleShape) {
        Text(label, color = if (selected) Bg else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

@Composable
private fun FeaturePage(title: String, description: String, items: List<String>) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(description, color = Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
        items.forEach { item ->
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item, color = White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Icon(Icons.Default.ChevronRight, null, tint = Muted)
                }
            }
        }
    }
}
