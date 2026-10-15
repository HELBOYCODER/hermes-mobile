package com.ersaz.hermesmobile.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object DownloadsExporter {
    fun mime(file: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    fun save(context: Context, file: File): Uri {
        require(file.isFile) { "The attached file is no longer available" }
        val name = GeneratedFiles.safeName(file.name)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime(file))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hermes")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Could not create a download" }
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) > 0)
                return uri
            } catch (error: Exception) { resolver.delete(uri, null, null); throw error }
        }
        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Hermes").apply { mkdirs() }
        var target = File(folder, name)
        var suffix = 1
        while (target.exists()) {
            val stem = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            target = File(folder, "$stem (${suffix++})$extension")
        }
        try { file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } } }
        catch (error: Exception) { target.delete(); throw error }
        return FileProvider.getUriForFile(context, context.packageName + ".files", target)
    }
}
