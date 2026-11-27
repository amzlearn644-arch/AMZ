package com.amz.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PalsScreen(pals: List<Pal>, activePalId: String?, onSelect: (Pal?) -> Unit, onSave: (List<Pal>) -> Unit) {
    var editing by remember { mutableStateOf<Pal?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Pal?>(null) }
    if (creating || editing != null) {
        val original = editing
        PalEditorDialog(original, { creating = false; editing = null }) { name, description, prompt, emoji ->
            val updated = if (original == null) pals + Pal(java.util.UUID.randomUUID().toString(), name, description, prompt, emoji)
                else pals.map { if (it.id == original.id) original.copy(name = name, description = description, systemPrompt = prompt, emoji = emoji) else it }
            onSave(updated); creating = false; editing = null
        }
    }
    deleting?.let { pal ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${'$'}{pal.name}?") },
            text = { Text("This assistant will be removed.") },
            confirmButton = { TextButton(onClick = {
                val updated = pals.filterNot { it.id == pal.id }; onSave(updated)
                if (activePalId == pal.id) onSelect(null); deleting = null
            }) { Text("Delete", color = Color(0xFFFF7777)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            containerColor = Color(0xFF15171D), titleContentColor = Color(0xFFF4F5F7), textContentColor = Color(0xFF9297A3))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your Pals", color = Color(0xFFF4F5F7), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Choose a personality for your next conversation.", color = Color(0xFF9297A3), fontSize = 12.sp)
            }
            Button(onClick = { creating = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB7F36B), contentColor = Color(0xFF090A0D)), shape = CircleShape) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Create") }
        }
        Spacer(Modifier.height(14.dp))
        Surface(color = Color(0xFF20232B), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Default AMZ AI", color = Color(0xFFF4F5F7), fontWeight = FontWeight.SemiBold); Text("Use the standard assistant personality", color = Color(0xFF9297A3), fontSize = 11.sp) }
                TextButton(onClick = { onSelect(null) }) { Text(if (activePalId == null) "Active" else "Use", color = Color(0xFFB7F36B)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(pals, key = { it.id }) { pal ->
                Surface(color = Color(0xFF15171D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pal.emoji, fontSize = 24.sp); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) { Text(pal.name, color = Color(0xFFF4F5F7), fontWeight = FontWeight.SemiBold); Text(pal.description, color = Color(0xFF9297A3), fontSize = 11.sp) }
                            if (!pal.id.startsWith("default-")) {
                                IconButton(onClick = { editing = pal }) { Icon(Icons.Default.Edit, "Edit", tint = Color(0xFF9297A3)) }
                                IconButton(onClick = { deleting = pal }) { Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF7777)) }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { onSelect(pal) }) { Text(if (activePalId == pal.id) "Selected for chat" else "Use this Pal", color = Color(0xFFB7F36B)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PalEditorDialog(initial: Pal?, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial?.id) { mutableStateOf(initial?.description.orEmpty()) }
    var prompt by remember(initial?.id) { mutableStateOf(initial?.systemPrompt.orEmpty()) }
    var emoji by remember(initial?.id) { mutableStateOf(initial?.emoji ?: "✨") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "Create a Pal" else "Edit Pal") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PalField(name, { name = it }, "Name"); PalField(emoji, { emoji = it.take(4) }, "Emoji")
            PalField(description, { description = it }, "Short description"); PalField(prompt, { prompt = it }, "Personality / System Prompt", 4)
            Text("The system prompt defines how this Pal behaves.", color = Color(0xFF9297A3), fontSize = 10.sp)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = { onSave(name.trim(), description.trim(), prompt.trim(), emoji.ifBlank { "✨" }) }) { Text("Save", color = Color(0xFFB7F36B)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Color(0xFF15171D), titleContentColor = Color(0xFFF4F5F7), textContentColor = Color(0xFFF4F5F7))
}

@Composable
private fun PalField(value: String, onChange: (String) -> Unit, placeholder: String, minLines: Int = 1) {
    BasicTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth().background(Color(0xFF20232B), RoundedCornerShape(10.dp)).padding(12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFF4F5F7), fontSize = 13.sp), minLines = minLines,
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = Color(0xFF9297A3), fontSize = 13.sp); inner() })
}