package org.fossify.camera.extensions

import android.content.Context
import org.fossify.camera.helpers.Config
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_ACCESS_COARSE_LOCATION
import org.fossify.commons.helpers.PERMISSION_ACCESS_FINE_LOCATION
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.config: Config get() = Config.newInstance(applicationContext)

fun Context.getOutputMediaFilePath(isPhoto: Boolean): String {
    val mediaStorageDir = File(config.savePhotosFolder)

    if (!mediaStorageDir.exists()) {
        if (!mediaStorageDir.mkdirs()) {
            return ""
        }
    }

    val mediaName = getRandomMediaName(isPhoto)
    return if (isPhoto) {
        "${mediaStorageDir.path}/$mediaName.jpg"
    } else {
        "${mediaStorageDir.path}/$mediaName.mp4"
    }
}

fun Context.getOutputMediaFileName(isPhoto: Boolean): String {
    val mediaName = getRandomMediaName(isPhoto)
    return if (isPhoto) {
        "$mediaName.jpg"
    } else {
        "$mediaName.mp4"
    }
}

fun getRandomMediaName(isPhoto: Boolean): String {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    return if (isPhoto) {
        "IMG_$timestamp"
    } else {
        "VID_$timestamp"
    }
}

fun Context.checkLocationPermission(): Boolean {
    return hasPermission(PERMISSION_ACCESS_FINE_LOCATION) || hasPermission(
        PERMISSION_ACCESS_COARSE_LOCATION
    )
}

// 取路径最后一级文件夹名，例如 /a/b/c -> c
fun String.getLastFolderName(): String {
    val trimmed = trimEnd('/')
    val parts = trimmed.split('/').filter { it.isNotEmpty() }
    return parts.lastOrNull() ?: trimmed
}

// 取路径最后两级文件夹名，例如 /a/b/c -> b/c；不足两级则返回能取到的部分
fun String.getLastTwoFolderNames(): String {
    val trimmed = trimEnd('/')
    val parts = trimmed.split('/').filter { it.isNotEmpty() }
    return when {
        parts.size >= 2 -> "${parts[parts.size - 2]}/${parts.last()}"
        parts.size == 1 -> parts.last()
        else -> trimmed
    }
}
