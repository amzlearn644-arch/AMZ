package com.amz.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.delay

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

private data class ChatMessage(val text: String, val user: Boolean, val seconds: Long = 0)

@Composable
private fun AMZApp() {
    var tab by remember { mutableStateOf("Chat") }
    var mode by remember { mutableStateOf("Fast") }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val messages = remember { mutableStateListOf(
        ChatMessage("سلام! من AMZ AI هستم. برای شروع، یک مدل محلی یا سرویس هوش مصنوعی را متصل کن.", false)
    ) }
    LaunchedEffect(busy) {
        while (busy) { delay(1000); elapsed++ }
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
                        Text("No model connected", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
                        items(messages) { msg ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.user) Arrangement.End else Arrangement.Start) {
                                Surface(color = if (msg.user) Color(0xFF28321F) else Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 310.dp)) {
                                    Column(Modifier.padding(15.dp)) {
                                        Text(msg.text, color = White, fontSize = 14.sp, lineHeight = 21.sp)
                                        if (!msg.user && msg.seconds > 0) Text("${msg.seconds}s · estimated tokens", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                            }
                        }
                        if (busy) item { Text("Preparing response…  ${elapsed}s", color = Accent, fontSize = 12.sp) }
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
                                    if (text.isNotEmpty() && !busy) {
                                        messages.add(ChatMessage(text, true)); draft = ""
                                        messages.add(ChatMessage("برای پاسخ‌گویی هنوز مدلی متصل نشده است. از بخش Models یک مدل GGUF اضافه کن یا در تنظیمات، سرور سازگار را پیکربندی کن.", false))
                                    }
                                }, enabled = draft.isNotBlank() && !busy) {
                                    Icon(Icons.Default.Send, null, tint = if (draft.isNotBlank()) Accent else Muted)
                                }
                            }
                        }
                    }
                }
                "Models" -> FeaturePage("Model library", "مدیریت مدل‌های GGUF، دریافت از Hugging Face و اجرای محلی در مرحله بعد به موتور استنتاج متصل می‌شود.", listOf("Browse models", "Downloaded models", "Import GGUF", "Hardware & benchmark"))
                "Pals" -> FeaturePage("Your Pals", "دستیارهای شخصی با شخصیت، System Prompt و تنظیمات مستقل.", listOf("Create a Pal", "Assistant presets", "Roleplay profiles"))
                else -> FeaturePage("Settings", "تنظیمات پیشرفته در حال آماده‌سازی است.", listOf("Inference parameters", "Context & memory", "Voice input / output", "Connected servers", "Appearance & language", "Export chats"))
            }
        }
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
