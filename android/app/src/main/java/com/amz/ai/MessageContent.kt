package com.amz.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class MarkdownPart(val code: Boolean, val language: String, val content: String)

@Composable
fun MessageContent(text: String, isUser: Boolean) {
    val clipboard = LocalClipboardManager.current
    val parts = remember(text) {
        text.split(96.toChar().toString().repeat(3)).mapIndexed { index, raw ->
            if (index % 2 == 0) MarkdownPart(false, "", raw)
            else {
                val newline = raw.indexOf('\n')
                val firstLine = if (newline >= 0) raw.substring(0, newline).trim() else ""
                val hasLanguage = newline >= 0 && firstLine.matches(Regex("[A-Za-z0-9_+#.-]{1,24}"))
                MarkdownPart(true, if (hasLanguage) firstLine else "", if (hasLanguage) raw.substring(newline + 1) else raw)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(15.dp)) {
        parts.forEach { part ->
            if (part.code) {
                Column(Modifier.fillMaxWidth().background(Color(0xFF0B0D11), RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(part.language.ifBlank { "CODE" }.uppercase(), color = Color(0xFFB7F36B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { clipboard.setText(AnnotatedString(part.content.trimEnd())) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy code", tint = Color(0xFF9297A3), modifier = Modifier.size(15.dp))
                        }
                    }
                    Text(part.content.trimEnd(), color = Color(0xFFE2E6ED), fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                part.content.split('\n').forEach { line ->
                    if (line.isNotBlank()) {
                        val heading = line.trimStart().startsWith("#")
                        val display = if (heading) line.trimStart().trimStart('#').trim() else line
                        Text(
                            inlineMarkdown(display),
                            color = Color(0xFFF4F5F7),
                            fontSize = if (heading) 15.sp else 14.sp,
                            lineHeight = 21.sp,
                            fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal
                        )
                    } else {
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

private fun inlineMarkdown(value: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("(\\*\\*.+?\\*\\*|"+96.toChar()+"[^"+96.toChar()+"]+"+96.toChar()+")")
    var cursor = 0
    pattern.findAll(value).forEach { match ->
        append(value.substring(cursor, match.range.first))
        val token = match.value
        if (token.startsWith("**")) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.removePrefix("**").removeSuffix("**")) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFB7F36B), background = Color(0xFF20232B))) {
                append(token.substring(1, token.length - 1))
            }
        }
        cursor = match.range.last + 1
    }
    append(value.substring(cursor))
}
