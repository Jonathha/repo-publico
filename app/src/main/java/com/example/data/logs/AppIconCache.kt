package com.example.data.logs

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cache de ícones já convertidos: evita decodificar o drawable a cada recomposição. */
object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(96)

    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                context.applicationContext.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(96, 96)
                    .asImageBitmap()
            } catch (_: Exception) {
                null
            }
        } ?: return null
        cache.put(packageName, bitmap)
        return bitmap
    }
}
