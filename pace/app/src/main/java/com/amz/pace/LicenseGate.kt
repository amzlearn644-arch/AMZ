package com.amz.pace

import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.security.MessageDigest

private const val LICENSE_PREF = "pace_license"
private const val PUBLIC_KEY_B64 = "TQ+TzQvCuE1uGaNOflTm9JW6htGVclVCC7gpRUnXsMc="
private const val LICENSE_PRODUCT = "AMZ-PACE"

private fun deviceFingerprint(context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    val raw = "AMZ-PACE|$androidId|com.amz.pace|device-license-v1"
    val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02X".format(it) }
}

private fun validateLicense(code: String, device: String): Boolean = try {
    val parts = code.trim().split(".")
    if (parts.size != 3 || parts[0] != "AMZP1") return false
    val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val signature = Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val data = JSONObject(String(payload, Charsets.UTF_8))
    if (data.optString("product") != LICENSE_PRODUCT ||
        data.optString("device") != device ||
        data.optInt("version") != 1
    ) return false

    val publicKey = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
    val verifier = Ed25519Signer()
    verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
    verifier.update(payload, 0, payload.size)
    verifier.verifySignature(signature)
} catch (_: Throwable) {
    false
}

@Composable
fun PaceApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var activation by remember {
        mutableStateOf(context.getSharedPreferences(LICENSE_PREF, 0).getString("license", "") ?: "")
    }
    val device = remember { deviceFingerprint(context) }
    val active = remember(activation, device) {
        activation.isNotBlank() && validateLicense(activation, device)
    }

    if (active) {
        PaceAppContent()
    } else {
        LicenseScreen(device, activation) { license ->
            context.getSharedPreferences(LICENSE_PREF, 0).edit().putString("license", license).apply()
            activation = license
        }
    }
}

@Composable
private fun LicenseScreen(device: String, initial: String, onActivate: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var code by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    var language by remember {
        mutableStateOf(context.getSharedPreferences(LICENSE_PREF, 0).getString("screen_language", "fa") ?: "fa")
    }
    var copied by remember { mutableStateOf(false) }

    val isEnglish = language == "en"
    val background = Color(0xFF101C32)
    val accent = Color(0xFF2DD4BF)
    val foreground = Color(0xFFF4F1E8)
    val secondary = Color(0xFFB7C3D2)
    val canSubmit = code.isNotBlank()

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = !isEnglish,
                            onClick = {
                                language = "fa"
                                context.getSharedPreferences(LICENSE_PREF, 0).edit().putString("screen_language", language).apply()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text("فارسی") }
                        SegmentedButton(
                            selected = isEnglish,
                            onClick = {
                                language = "en"
                                context.getSharedPreferences(LICENSE_PREF, 0).edit().putString("screen_language", language).apply()
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text("English") }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text("AMZ", color = accent, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
                Spacer(Modifier.height(6.dp))
                Text("Pace", color = foreground, fontSize = 38.sp, fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(18.dp))
                Text(
                    if (isEnglish) "Activate AMZ Pace" else "فعال‌سازی AMZ Pace",
                    color = foreground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isEnglish)
                        "To use the app, send your device ID to our Telegram account and receive a license created for this device."
                    else
                        "برای استفاده از برنامه، شناسه دستگاه را به آیدی تلگرام ما بفرستید تا لایسنس مخصوص همین گوشی برایتان صادر شود.",
                    color = secondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2B43)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (isEnglish) "Your device ID" else "شناسه اختصاصی دستگاه شما",
                            color = accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                device,
                                color = foreground,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                        TextButton(
                            onClick = {
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                manager.setPrimaryClip(android.content.ClipData.newPlainText("AMZ Pace Device ID", device))
                                copied = true
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    copied && isEnglish -> "Copied"
                                    copied -> "شناسه کپی شد"
                                    isEnglish -> "Copy device ID"
                                    else -> "کپی شناسه دستگاه"
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        error = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (isEnglish) "License key" else "کد لایسنس") },
                    placeholder = { Text(if (isEnglish) "Paste your license key here" else "کد لایسنس را اینجا وارد کنید") },
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = foreground,
                        unfocusedTextColor = foreground,
                        focusedBorderColor = accent,
                        unfocusedBorderColor = Color(0xFF52647C),
                        focusedLabelColor = accent,
                        unfocusedLabelColor = secondary,
                        cursorColor = accent
                    )
                )

                if (error.isNotBlank()) {
                    Text(error, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val cleaned = code.trim()
                        if (validateLicense(cleaned, device)) {
                            onActivate(cleaned)
                        } else {
                            error = if (isEnglish)
                                "This license key is invalid or was issued for another device."
                            else
                                "کد لایسنس معتبر نیست یا برای دستگاه دیگری صادر شده است."
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = background,
                        disabledContainerColor = Color(0xFF35465A),
                        disabledContentColor = Color(0xFF8B9AAF)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        if (isEnglish) "Activate license" else "فعال‌سازی لایسنس",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    if (isEnglish) "This license works only on the device linked to its ID."
                    else "این لایسنس فقط روی دستگاهی فعال می‌شود که شناسه آن برای صدور لایسنس ارسال شده است.",
                    color = secondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(20.dp))
                Text("Created by Amin", color = Color(0xFF71839A), fontSize = 11.sp)
            }
        }
    }
}
