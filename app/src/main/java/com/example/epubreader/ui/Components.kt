package com.example.epubreader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.epubreader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COVER_TARGET_WIDTH = 144
private const val COVER_TARGET_HEIGHT = 216

@Composable
fun BookCover(title: String, coverPath: String?) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, coverPath) {
        value = if (coverPath == null) null
        else withContext(Dispatchers.IO) { decodeCoverSampled(coverPath) }
    }
    Box(
        modifier = Modifier
            .width(72.dp)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            val bmp = bitmap!!
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = context.getString(R.string.cover_label, title),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                context.getString(R.string.cover_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun decodeCoverSampled(path: String): Bitmap? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val (origW, origH) = (opts.outWidth to opts.outHeight)
        if (origW <= 0 || origH <= 0) return null
        var sampleSize = 1
        while (origW / sampleSize > COVER_TARGET_WIDTH * 2 || origH / sampleSize > COVER_TARGET_HEIGHT * 2) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inMutable = false
        }
        BitmapFactory.decodeFile(path, decodeOpts)
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ReaderControlButton(
    label: String,
    icon: ImageVector,
    text: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Icon(icon, label)
        }
        if (text != null) {
            Text(text, fontSize = 11.sp, maxLines = 1)
        }
    }
}
