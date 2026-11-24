package com.amz.pace

import android.content.Context
import android.provider.Settings
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.json.JSONObject
import java.security.MessageDigest

private const val LICENSE_PREF="pace_license"
private const val PUBLIC_KEY_B64="TQ+TzQvCuE1uGaNOflTm9JW6htGVclVCC7gpRUnXsMc="
private const val LICENSE_PRODUCT="AMZ-PACE"
private fun deviceFingerprint(c:Context):String {
 val androidId=Settings.Secure.getString(c.contentResolver,Settings.Secure.ANDROID_ID).orEmpty()
 val raw="AMZ-PACE|$androidId|com.amz.pace|device-license-v1"
 val hash=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
 return hash.joinToString("") { "%02X".format(it) }
}
private fun validateLicense(code:String,device:String):Boolean {
 return try {
 val p=code.trim().split(".")
 if(p.size!=3||p[0]!="AMZP1") return false
 val payload=Base64.decode(p[1],Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
 val signature=Base64.decode(p[2],Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
 val data=JSONObject(String(payload,Charsets.UTF_8))
 if(data.optString("product")!=LICENSE_PRODUCT||data.optString("device")!=device||data.optInt("version")!=1) return false
 val pub=Base64.decode(PUBLIC_KEY_B64,Base64.DEFAULT)
 val verifier=Ed25519Signer()
 verifier.init(false,Ed25519PublicKeyParameters(pub,0))
 verifier.update(payload,0,payload.size)
 verifier.verifySignature(signature)
} catch(_:Throwable){false}
}
@Composable fun PaceApp(){
 val c= androidx.compose.ui.platform.LocalContext.current
 var activation by remember { mutableStateOf(c.getSharedPreferences(LICENSE_PREF,0).getString("license","") ?: "") }
 val device= remember { deviceFingerprint(c) }
 val active=remember(activation,device){activation.isNotBlank()&&validateLicense(activation,device)}
 if(active) PaceAppContent() else LicenseScreen(device,activation,{activation=it;c.getSharedPreferences(LICENSE_PREF,0).edit().putString("license",it).apply()})
}
@Composable private fun LicenseScreen(device:String,initial:String,onActivate:(String)->Unit){
 val clipboard = LocalClipboardManager.current
 var code by remember{mutableStateOf(initial)}
 var error by remember{mutableStateOf("")}
 val bg=Color(0xFF101C32);val gold=Color(0xFF2DD4BF);val paper=Color(0xFFF4F1E8)
 Surface(Modifier.fillMaxSize(),color=bg){
 Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
 Text("AMZ",color=gold,fontSize=16.sp,fontWeight=FontWeight.Black,letterSpacing=5.sp)
 Spacer(Modifier.height(10.dp))
 Text("Pace",color=paper,fontSize=38.sp,fontWeight=FontWeight.Bold)
 Text("برای دسترسی به AMZ Pace نیازمند تهیه لایسنس هستید.",color=paper,fontSize=17.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center,modifier=Modifier.padding(top=18.dp))
 Text("برای تهیه لایسنس، کد زیر را به آیدی تلگرام @Ammmin_33 ارسال کنید و لایسنس اختصاصی گوشی خود را دریافت کنید.",color=Color(0xFFB7C3D2),textAlign=TextAlign.Center,modifier=Modifier.padding(vertical=14.dp))
 Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF1B2B43)),shape=RoundedCornerShape(16.dp)){
 Column(Modifier.padding(14.dp),horizontalAlignment=Alignment.CenterHorizontally){
 Text("شناسه اختصاصی دستگاه",color=gold,fontSize=12.sp)
 Text(device,color=paper,fontSize=13.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)
 TextButton(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(device))}){Icon(Icons.Default.ContentCopy,null);Text("کپی شناسه")}
 }}
 Spacer(Modifier.height(16.dp))
 OutlinedTextField(code,{code=it;error=""},modifier=Modifier.fillMaxWidth(),label={Text("کد لایسنس")},minLines=2,colors=OutlinedTextFieldDefaults.colors(focusedTextColor=paper,unfocusedTextColor=paper,focusedBorderColor=gold,unfocusedBorderColor=Color(0xFF52647C),focusedLabelColor=gold,unfocusedLabelColor=Color(0xFFB7C3D2)))
 if(error.isNotBlank())Text(error,color=Color(0xFFFF8A80),modifier=Modifier.padding(8.dp))
 Button(onClick={if(validateLicense(code,device))onActivate(code.trim()) else error="کد لایسنس معتبر نیست یا برای این گوشی صادر نشده است."},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=gold,contentColor=bg)){Text("فعال‌سازی لایسنس",fontWeight=FontWeight.Bold)}
 Text("این لایسنس فقط روی همین گوشی قابل استفاده است.",color=Color(0xFFB7C3D2),fontSize=12.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(top=14.dp))
 Text("Created by Amin",color=Color(0xFF71839A),fontSize=11.sp,modifier=Modifier.padding(top=22.dp))
 }
 }
}
