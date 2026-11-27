package com.amz.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class HuggingFaceModel(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val ggufFiles: List<String>
)

data class DownloadProgress(val fileName: String, val received: Long, val total: Long) {
    val percent: Int get() = if (total > 0) ((received * 100) / total).toInt() else 0
}

class ModelRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<HuggingFaceModel> = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://huggingface.co/api/models?search=$encoded&filter=gguf&sort=downloads&direction=-1&limit=25"
        val request = Request.Builder().url(url).header("User-Agent", "AMZ-AI-Android/0.2").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Hugging Face پاسخ نداد: HTTP ${response.code}")
            val array = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = item.optString("id")
                    val siblings = item.optJSONArray("siblings") ?: JSONArray()
                    val gguf = buildList {
                        for (j in 0 until siblings.length()) {
                            val name = siblings.optJSONObject(j)?.optString("rfilename").orEmpty()
                            if (name.endsWith(".gguf", true)) add(name)
                        }
                    }
                    if (id.isNotBlank() && gguf.isNotEmpty()) add(
                        HuggingFaceModel(id, item.optLong("downloads"), item.optLong("likes"), gguf)
                    )
                }
            }
        }
    }

    suspend fun download(modelId: String, fileName: String, onProgress: (DownloadProgress) -> Unit): File =
        withContext(Dispatchers.IO) {
            val safeName = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(File(context.filesDir, "models").apply { mkdirs() }, safeName)
            val url = "https://huggingface.co/$modelId/resolve/main/${fileName.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}"
            val request = Request.Builder().url(url).header("User-Agent", "AMZ-AI-Android/0.2").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("دانلود ناموفق بود: HTTP ${response.code}")
                val body = response.body ?: error("پاسخ دانلود خالی است")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(1024 * 256)
                        var received = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            onProgress(DownloadProgress(safeName, received, total))
                        }
                        output.fd.sync()
                    }
                }
            }
            target
        }

    suspend fun importGguf(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = (uri.lastPathSegment ?: "imported-model.gguf").substringAfterLast('/')
        require(name.endsWith(".gguf", true)) { "فقط فایل GGUF قابل وارد کردن است." }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(File(context.filesDir, "models").apply { mkdirs() }, safeName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "امکان خواندن فایل انتخاب‌شده وجود ندارد." }
            FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
        }
        target
    }

    fun installedModels(): List<File> =
        File(context.filesDir, "models").listFiles()?.filter { it.isFile && it.extension.equals("gguf", true) }?.sortedBy { it.name } ?: emptyList()
}
