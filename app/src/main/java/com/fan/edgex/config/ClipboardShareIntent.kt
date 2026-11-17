package com.fan.edgex.config

import android.content.ClipData
import android.content.Intent
import android.net.Uri

internal fun clipboardShareIntent(text: String?, images: List<Uri>, imageMimeType: String): Intent {
    val multiple = images.size > 1
    return Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        type = if (images.isEmpty()) "text/plain" else imageMimeType
        if (text != null) {
            if (multiple) {
                putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf(text))
            } else {
                putExtra(Intent.EXTRA_TEXT, text)
            }
        }
        if (images.isNotEmpty()) {
            if (multiple) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images))
            } else {
                putExtra(Intent.EXTRA_STREAM, images.single())
            }
            clipData = ClipData(
                "Clipboard", arrayOf(imageMimeType),
                ClipData.Item(text, null, images.first())
            ).apply {
                images.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
