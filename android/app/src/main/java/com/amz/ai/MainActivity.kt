package com.amz.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

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

    override fun onDestroy() {
        // Stop any active decode, then release the native context and model.
        // nativeUnload waits for the inference mutex, so cleanup cannot free a
        // model while the generation loop is still using it.
        NativeLlama.nativeCancelGeneration()
        Thread({ NativeLlama.nativeUnload() }, "amz-ai-native-cleanup").start()
        super.onDestroy()
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

@Composable
private fun AMZApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { ModelRepository(context) }
    val chatHistory = remember { ChatHistoryRepository(context) }
    val initialConversations = remember { chatHistory.loadConversations() }
    var conversations by remember { mutableStateOf(initialConversations) }
    var activeConversationId by remember { mutableStateOf(initialConversations.first().id) }
    var conversationMenuExpanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    val activeConversation = conversations.firstOrNull { it.id == activeConversationId } ?: conversations.first()
    val palRepository = remember { PalRepository(context) }
    var pals by remember { mutableStateOf(palRepository.load()) }
    var activePalId by remember { mutableStateOf(palRepository.activeId()) }
    val activePal = pals.firstOrNull { it.id == activePalId }
    val settingsRepository = remember { SettingsRepository(context) }
    val initialSettings = remember { settingsRepository.load() }
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
    var contextSize by remember { mutableIntStateOf(initialSettings.contextSize) }
    var temperature by remember { mutableFloatStateOf(initialSettings.temperature) }
    var topP by remember { mutableFloatStateOf(initialSettings.topP) }
    var topK by remember { mutableIntStateOf(initialSettings.topK) }
    var repeatPenalty by remember { mutableFloatStateOf(initialSettings.repeatPenalty) }
    var showClearHistory by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply { addAll(activeConversation.messages) }
    }
    LaunchedEffect(contextSize, temperature, topP, topK, repeatPenalty) {
        withContext(Dispatchers.IO) { settingsRepository.save(AppSettings(contextSize, temperature, topP, topK, repeatPenalty)) }
    }
    val historySnapshot = messages.joinToString("\u001e") { (if (it.user) "U" else "A") + it.text }
    LaunchedEffect(historySnapshot, activeConversationId) {
        conversations = conversations.map { conversation ->
            if (conversation.id == activeConversationId) conversation.copy(messages = messages.toList(), updatedAt = System.currentTimeMillis()) else conversation
        }
    }
    LaunchedEffect(conversations) {
        withContext(Dispatchers.IO) { chatHistory.saveConversations(conversations) }
    }
    fun switchConversation(conversation: Conversation) {
        if (generating || conversation.id == activeConversationId) return
        conversations = conversations.map { current ->
            if (current.id == activeConversationId) current.copy(messages = messages.toList(), updatedAt = System.currentTimeMillis()) else current
        }
        activeConversationId = conversation.id
        messages.clear()
        messages.addAll(conversation.messages)
        draft = ""
    }
    fun createConversation() {
        if (generating) return
        conversations = conversations.map { current ->
            if (current.id == activeConversationId) current.copy(messages = messages.toList(), updatedAt = System.currentTimeMillis()) else current
        }
        val fresh = Conversation.create()
        conversations = listOf(fresh) + conversations
        activeConversationId = fresh.id
        messages.clear()
        messages.add(ChatMessage("سلام! برای اجرای آفلاین، ابتدا یک مدل GGUF وارد یا دانلود کن.", false))
        draft = ""
        conversationMenuExpanded = false
    }
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
    if (showClearHistory) {
        AlertDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text("پاک کردن گفتگو؟") },
            text = { Text("تمام پیام‌های ذخیره‌شده این گفتگو حذف می‌شوند.") },
            confirmButton = {
                TextButton(onClick = {
                    messages.clear()
                    messages.add(ChatMessage("گفتگوی تازه شروع شد. پیام بده تا با هم ادامه بدهیم.", false))
                    showClearHistory = false
                }) { Text("پاک کردن", color = Color(0xFFFF7777)) }
            },
            dismissButton = { TextButton(onClick = { showClearHistory = false }) { Text("انصراف") } },
            containerColor = Panel,
            titleContentColor = White,
            textContentColor = Muted
        )
    }
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("تغییر نام گفتگو") },
            text = {
                BasicTextField(
                    value = renameText, onValueChange = { renameText = it.take(48) },
                    modifier = Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(10.dp)).padding(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 14.sp), singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = renameTarget
                    val title = renameText.trim()
                    if (target != null && title.isNotEmpty()) conversations = conversations.map { if (it.id == target.id) it.copy(title = title) else it }
                    renameTarget = null
                }) { Text("ذخیره", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("انصراف") } },
            containerColor = Panel, titleContentColor = White
        )
    }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف گفتگو؟") },
            text = { Text("پیام‌های این گفتگو برای همیشه از حافظه برنامه حذف می‌شوند.") },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget
                    if (target != null && conversations.size > 1) {
                        val remaining = conversations.filterNot { it.id == target.id }
                        conversations = remaining
                        if (activeConversationId == target.id) {
                            activeConversationId = remaining.first().id
                            messages.clear()
                            messages.addAll(remaining.first().messages)
                        }
                    }
                    deleteTarget = null
                }) { Text("حذف", color = Color(0xFFFF7777)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("انصراف") } },
            containerColor = Panel, titleContentColor = White
        )
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
                    Text(if (tab == "Chat") activeConversation.title else tab, color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                if (tab == "Chat") {
                    Box {
                        IconButton(onClick = { conversationMenuExpanded = true }, enabled = !generating) {
                            Icon(Icons.Default.Forum, "Conversations", tint = Accent)
                        }
                        DropdownMenu(expanded = conversationMenuExpanded, onDismissRequest = { conversationMenuExpanded = false }, modifier = Modifier.background(Panel)) {
                            DropdownMenuItem(
                                text = { Text("گفتگوی جدید", color = Accent) },
                                leadingIcon = { Icon(Icons.Default.Add, null, tint = Accent) },
                                onClick = { createConversation() }, enabled = !generating
                            )
                            HorizontalDivider(color = Surface2)
                            conversations.forEach { conversation ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(conversation.title, color = if (conversation.id == activeConversationId) Accent else White, maxLines = 1)
                                            Text("${conversation.messages.count { it.user }} پیام", color = Muted, fontSize = 10.sp)
                                        }
                                    },
                                    leadingIcon = { Icon(if (conversation.id == activeConversationId) Icons.Default.CheckCircle else Icons.Default.ChatBubbleOutline, null, tint = if (conversation.id == activeConversationId) Accent else Muted) },
                                    trailingIcon = {
                                        Row {
                                            IconButton(onClick = { renameTarget = conversation; renameText = conversation.title; conversationMenuExpanded = false }) {
                                                Icon(Icons.Default.Edit, "Rename", tint = Muted)
                                            }
                                            if (conversations.size > 1) IconButton(onClick = { deleteTarget = conversation; conversationMenuExpanded = false }) {
                                                Icon(Icons.Default.DeleteOutline, "Delete", tint = Color(0xFFFF7777))
                                            }
                                        }
                                    },
                                    onClick = { switchConversation(conversation); conversationMenuExpanded = false },
                                    enabled = !generating
                                )
                            }
                        }
                    }
                }
                Surface(color = Surface2, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(Accent, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text("LOCAL FIRST", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (tab == "Chat" && messages.size > 1) {
                    IconButton(onClick = { showClearHistory = true }) { Icon(Icons.Default.Delete, "Clear chat", tint = Muted) }
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
                                    MessageContent(msg.text, isUser = msg.user)
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
                                if (generating) {
                                    IconButton(onClick = {
                                        inference.cancelGeneration()
                                        status = "درخواست توقف ارسال شد…"
                                    }) {
                                        Icon(Icons.Default.StopCircle, "Stop generation", tint = Color(0xFFFF7777))
                                    }
                                } else {
                                    IconButton(onClick = {
                                        val text = draft.trim()
                                        if (text.isNotEmpty()) {
                                            messages.add(ChatMessage(text, true)); draft = ""
                                            if (!modelLoaded) {
                                                messages.add(ChatMessage("ابتدا یک مدل را از بخش Models بارگذاری کن.", false))
                                            } else {
                                                generating = true
                                                inference.beginGeneration()
                                                scope.launch {
                                                    val started = System.currentTimeMillis()
                                                    val prompt = inference.formatPrompt(messages.toList(), mode, activePal?.systemPrompt)
                                                    messages.add(ChatMessage("", false))
                                                    val answerIndex = messages.lastIndex
                                                    var outputTokens = 0
                                                    val result = runCatching {
                                                        inference.generate(prompt, 256, SamplingSettings(temperature, topP, topK, repeatPenalty)) { chunk, generatedTokenCount ->
                                                            outputTokens = generatedTokenCount
                                                            val current = messages.getOrNull(answerIndex)
                                                            if (current != null) messages[answerIndex] = current.copy(text = current.text + chunk)
                                                        }
                                                    }
                                                    val seconds = (System.currentTimeMillis() - started) / 1000.0
                                                    result.onSuccess { answer ->
                                                        val current = messages.getOrNull(answerIndex)
                                                        if (current != null && answer.startsWith("ERROR:")) messages[answerIndex] = current.copy(text = answer)
                                                        else if (current != null) {
                                                            val cancelled = inference.wasGenerationCancelled()
                                                            val metrics = outputTokens.toString() + " output tokens · " + (if (seconds > 0) "%.1f".format(outputTokens / seconds) + " tok/s · " else "") + "%.1f".format(seconds) + "s"
                                                            val ending = if (cancelled) "⏹ متوقف شد · " + metrics else metrics
                                                            messages[answerIndex] = current.copy(text = current.text + "\\n\\n" + ending)
                                                        }
                                                    }.onFailure { err ->
                                                        val current = messages.getOrNull(answerIndex)
                                                        if (current != null) messages[answerIndex] = current.copy(text = "خطای اجرای مدل: " + (err.message ?: "unknown"))
                                                    }
                                                    generating = false
                                                }
                                            }
                                        }
                                    }, enabled = draft.isNotBlank()) {
                                        Icon(Icons.Default.Send, null, tint = if (draft.isNotBlank()) Accent else Muted)
                                    }
                                }
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
                "Pals" -> PalsScreen(
    pals = pals,
    activePalId = activePalId,
    onSelect = { pal ->
        activePalId = pal?.id
        palRepository.setActive(pal?.id)
        status = if (pal == null) "دستیار پیش‌فرض AMZ AI انتخاب شد." else pal.name + " برای گفتگوی بعدی انتخاب شد."
    },
    onSave = { updated ->
        pals = updated
        palRepository.save(updated)
        if (activePalId != null && updated.none { it.id == activePalId }) {
            activePalId = null
            palRepository.setActive(null)
        }
    }
)
                else -> SettingsPage(
                    contextSize, temperature, topP, topK, repeatPenalty,
                    onContextSizeChange = { contextSize = it },
                    onTemperatureChange = { temperature = it },
                    onTopPChange = { topP = it },
                    onTopKChange = { topK = it },
                    onRepeatPenaltyChange = { repeatPenalty = it },
                    onApplyContext = {
                        val file = selectedModel
                        if (file == null) status = "ابتدا یک مدل انتخاب کن."
                        else scope.launch {
                            loading = true
                            status = "در حال بارگذاری دوباره مدل…"
                            runCatching { inference.load(file.absolutePath, contextSize) }
                                .onSuccess { modelLoaded = it == "OK"; status = if (modelLoaded) "مدل با Context جدید بارگذاری شد." else it }
                                .onFailure { modelLoaded = false; status = it.message ?: "خطا در بارگذاری مدل" }
                            loading = false
                        }
                    },
                    status = status
                )
            }
        }
    }
}


@Composable
private fun SettingsPage(
    contextSize: Int, temperature: Float, topP: Float, topK: Int, repeatPenalty: Float,
    onContextSizeChange: (Int) -> Unit, onTemperatureChange: (Float) -> Unit,
    onTopPChange: (Float) -> Unit, onTopKChange: (Int) -> Unit,
    onRepeatPenaltyChange: (Float) -> Unit, onApplyContext: () -> Unit, status: String
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Inference settings", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("تنظیمات به‌صورت خودکار ذخیره می‌شوند.", color = Muted, fontSize = 12.sp)
        SettingSlider("Temperature", "%.2f".format(temperature), temperature, 0f, 2f, 19, onTemperatureChange)
        SettingSlider("Top-P", "%.2f".format(topP), topP, 0.05f, 1f, 18, onTopPChange)
        SettingSlider("Top-K", topK.toString(), topK.toFloat(), 0f, 100f, 19) { onTopKChange(it.toInt()) }
        SettingSlider("Repeat penalty", "%.2f".format(repeatPenalty), repeatPenalty, 1f, 1.5f, 19, onRepeatPenaltyChange)
        Divider(color = Surface2)
        SettingSlider("Context length", contextSize.toString(), contextSize.toFloat(), 512f, 8192f, 14) { onContextSizeChange(it.toInt()) }
        Text("برای اعمال Context جدید، مدل باید دوباره بارگذاری شود.", color = Muted, fontSize = 11.sp)
        Button(onClick = onApplyContext, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg), shape = CircleShape) { Text("Apply context / reload model") }
        if (status.isNotBlank()) Text(status, color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(20.dp))
    }
}
@Composable
private fun SettingSlider(title: String, label: String, value: Float, min: Float, max: Float, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(label, color = Accent, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max, steps = steps, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
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
