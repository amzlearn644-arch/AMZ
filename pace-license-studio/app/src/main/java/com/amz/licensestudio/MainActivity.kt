package com.amz.licensestudio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val KEY_FILE = "amz_pace_signing_key.pk8"
private const val ACCESS_PIN = "4172"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StudioScreen() }
    }
}

private fun createKeyPair(keyFile: File): String {
    require(!keyFile.exists() || keyFile.length() == 0L) {
        "کلید قبلاً ساخته شده است؛ برای جلوگیری از باطل‌شدن لایسنس‌های قبلی، کلید جدید نسازید."
    }
    val seed = ByteArray(32)
    SecureRandom().nextBytes(seed)
    val privateKey = Ed25519PrivateKeyParameters(seed, 0)
    keyFile.writeBytes(seed)
    return Base64.encodeToString(privateKey.generatePublicKey().encoded, Base64.NO_WRAP)
}

private fun derivePublicKey(seed: ByteArray): String {
    require(seed.size == 32) { "فایل کلید خصوصی ذخیره‌شده معتبر نیست." }
    return Base64.encodeToString(
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded,
        Base64.NO_WRAP
    )
}

private fun makeLicense(seed: ByteArray, deviceId: String): String {
    val normalized = deviceId.trim().uppercase(Locale.ROOT)
    require(normalized.matches(Regex("[0-9A-F]{64}"))) {
        "شناسه دستگاه باید دقیقاً ۶۴ رقم هگزادسیمال باشد."
    }
    val payload = JSONObject()
        .put("version", 1)
        .put("product", "AMZ-PACE")
        .put("device", normalized)
        .put("issued", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        .toString()
        .toByteArray(Charsets.UTF_8)
    val signer = Ed25519Signer()
    signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
    signer.update(payload, 0, payload.size)
    val signature = signer.generateSignature()
    val p = Base64.encodeToString(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val s = Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    return "AMZP1.$p.$s"
}

@Composable
private fun StudioScreen() {
    var pin by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf("") }
    val bg = Color(0xFF0B1220)
    val mint = Color(0xFF55D6BE)
    val white = Color(0xFFF2F5F8)
    MaterialTheme(colorScheme = darkColorScheme(primary = mint, onPrimary = bg, background = bg, surface = Color(0xFF172338), onSurface = white)) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            if (unlocked) {
                StudioScreenUnlocked()
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.Security, null, tint = mint, modifier = Modifier.size(46.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("AMZ License Studio", color = white, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(22.dp))
                    OutlinedTextField(value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4); pinError = "" },
                        modifier = Modifier.fillMaxWidth(), label = { Text("رمز ورود") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (pinError.isNotBlank()) Text(pinError, color = Color(0xFFFF827C), modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { if (pin == ACCESS_PIN) unlocked = true else { pinError = "رمز ورود اشتباه است."; pin = "" } },
                        enabled = pin.length == 4, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("ورود") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioScreenUnlocked() {
    val context = LocalContext.current
    val keyFile = remember { File(context.filesDir, KEY_FILE) }
    var keyReady by remember { mutableStateOf(keyFile.exists() && keyFile.length() > 0) }
    var deviceId by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf(if (keyReady) runCatching { derivePublicKey(keyFile.readBytes()) }.getOrDefault("") else "") }
    var keyVisible by remember { mutableStateOf(false) }

    val bg = Color(0xFF0B1220)
    val panel = Color(0xFF172338)
    val mint = Color(0xFF55D6BE)
    val white = Color(0xFFF2F5F8)

    MaterialTheme(colorScheme = darkColorScheme(
        primary = mint, onPrimary = bg, background = bg, surface = panel,
        onSurface = white, onBackground = white, error = Color(0xFFFF827C)
    )) {
        Surface(Modifier.fillMaxSize(), color = bg) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(18.dp))
                Icon(Icons.Default.Security, null, tint = mint, modifier = Modifier.size(42.dp))
                Text("AMZ", color = mint, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
                Text("License Studio", color = white, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("ابزار اختصاصی صدور لایسنس AMZ Pace", color = Color(0xFF9EADBF), fontSize = 13.sp)
                Spacer(Modifier.height(24.dp))

                Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Key, null, tint = mint)
                            Spacer(Modifier.width(8.dp))
                            Text("کلید خصوصی امضای لایسنس", fontWeight = FontWeight.Bold, color = white)
                            Spacer(Modifier.weight(1f))
                            Text(if (keyReady) "آماده صدور" else "بارگذاری نشده",
                                color = if (keyReady) mint else Color(0xFFFFC66D), fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (keyReady)
                                "جفت‌کلید این برنامه ساخته شده و کلید خصوصی فقط در حافظه داخلی برنامه نگهداری می‌شود. کلید عمومی را کپی کن و برای تنظیم نسخه AMZ Pace به سازنده بده."
                            else
                                "برای شروع، یک جفت‌کلید Ed25519 روی همین گوشی بساز. کلید خصوصی در حافظه داخلی برنامه می‌ماند و فقط کلید عمومی را باید برای تنظیم AMZ Pace ارسال کنی. ساخت کلید فقط یک‌بار انجام می‌شود.",
                            color = Color(0xFFAFBDCE), fontSize = 12.sp, lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        if (!keyReady) {
                            Button(
                                onClick = {
                                    try {
                                        publicKey = createKeyPair(keyFile)
                                        keyReady = true
                                        error = ""
                                        Toast.makeText(context, "جفت‌کلید با موفقیت ساخته شد. از کلید خصوصی نسخه پشتیبان امن بگیر.", Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        error = e.message ?: "ساخت کلید انجام نشد."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("ساخت جفت‌کلید امن روی این گوشی") }
                        }
                        if (keyReady) {
                            Text("کلید عمومی (برای ارسال به سازنده AMZ Pace)", color = mint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(publicKey, color = white, fontSize = 11.sp)
                            OutlinedButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("AMZ Pace Public Key", publicKey))
                                Toast.makeText(context, "کلید عمومی کپی شد. فقط همین کلید را ارسال کن.", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.fillMaxWidth(), enabled = publicKey.isNotBlank()) {
                                Icon(Icons.Default.ContentCopy, null)
                                Spacer(Modifier.width(8.dp))
                                Text("کپی کلید عمومی")
                            }

                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { keyVisible = !keyVisible }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (keyVisible) "مخفی‌کردن کلید خصوصی" else "نمایش کلید خصوصی")
                            }
                            if (keyVisible) {
                                val privateKeyText = Base64.encodeToString(keyFile.readBytes(), Base64.NO_WRAP)
                                Spacer(Modifier.height(8.dp))
                                Text(privateKeyText, color = white, fontSize = 11.sp)
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("AMZ Pace Private Key", privateKeyText))
                                    Toast.makeText(context, "کلید خصوصی کپی شد؛ آن را محرمانه نگه‌دار.", Toast.LENGTH_LONG).show()
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.ContentCopy, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("کپی کلید خصوصی")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "پس از دریافت شناسه دستگاه مشتری، آن را در کادر زیر وارد کن. شناسه باید ۶۴ کاراکتر باشد.",
                    color = Color(0xFFAFBDCE), fontSize = 13.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = {
                        deviceId = it.uppercase(Locale.ROOT).filter { c -> c.isLetterOrDigit() }.take(64)
                        license = ""
                        error = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("شناسه دستگاه مشتری") },
                    placeholder = { Text("شناسه ۶۴ کاراکتری را وارد یا جای‌گذاری کنید") },
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = white, unfocusedTextColor = white,
                        focusedBorderColor = mint, unfocusedBorderColor = Color(0xFF53657C),
                        focusedLabelColor = mint, unfocusedLabelColor = Color(0xFF9EADBF)
                    )
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        try {
                            val seed = keyFile.readBytes()
                            license = makeLicense(seed, deviceId)
                            error = ""
                        } catch (e: Exception) {
                            license = ""
                            error = e.message ?: "صدور لایسنس انجام نشد. اطلاعات واردشده را بررسی کن."
                        }
                    },
                    enabled = keyReady && deviceId.length == 64,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("صدور لایسنس برای این دستگاه", fontWeight = FontWeight.Bold) }

                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFFF827C), textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp), lineHeight = 19.sp)
                }
                if (license.isNotBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("لایسنس صادرشده", color = mint, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text("این کد را برای مشتری ارسال کن تا در صفحه فعال‌سازی AMZ Pace وارد کند.",
                                color = Color(0xFFAFBDCE), fontSize = 12.sp, lineHeight = 18.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(license, color = white, fontSize = 11.sp)
                            Spacer(Modifier.height(12.dp))
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            OutlinedButton(
                                onClick = {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("AMZ Pace License", license))
                                    Toast.makeText(context, "کد لایسنس کپی شد. اکنون می‌توانی آن را برای مشتری بفرستی.", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, null)
                                Spacer(Modifier.width(8.dp))
                                Text("کپی کد لایسنس")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "نکته امنیتی: کلید خصوصی فقط در حافظه داخلی این برنامه ذخیره می‌شود و هرگز به AMZ Pace یا GitHub ارسال نمی‌شود. از کلید خصوصی یک نسخه پشتیبان امن و آفلاین نگه‌دار. با حذف داده‌های برنامه، کلید ذخیره‌شده نیز پاک می‌شود. ساخت کلید تازه، لایسنس‌های قبلی را نامعتبر می‌کند.",
                    color = Color(0xFF8999AD), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 17.sp
                )
                Text("ساخته‌شده توسط Amin", color = Color(0xFF66778E), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 18.dp, bottom = 20.dp))
            }
        }
    }
}
