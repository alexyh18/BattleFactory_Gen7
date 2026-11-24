package com.usumfactory.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * A decoded animated sprite: the GIF's frames as ImageBitmaps plus each frame's duration (ms).
 * [frameAt] maps an elapsed time to the correct looping frame.
 */
class AnimatedSprite(val frames: List<ImageBitmap>, val delaysMs: List<Int>) {
    val totalMs: Int = delaysMs.sum().coerceAtLeast(1)

    fun frameAt(elapsedMs: Long): ImageBitmap {
        if (frames.size == 1) return frames[0]
        var t = (elapsedMs % totalMs).toInt()
        for (i in frames.indices) {
            t -= delaysMs[i]
            if (t < 0) return frames[i]
        }
        return frames.last()
    }
}

/** Decodes and caches sprites. Static PNGs from assets/sprites; animated GIFs from assets/anim. */
class Sprites(private val open: (String) -> java.io.InputStream) {
    constructor(assets: AssetManager) : this({ path: String -> assets.open(path) })

    private val cache = HashMap<String, ImageBitmap?>()
    private val animCache = HashMap<String, AnimatedSprite?>()

    private fun load(path: String): ImageBitmap? = cache.getOrPut(path) {
        runCatching { open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }

    /**
     * The animated gen5ani sprite for [species]/[side], or null when there is no GIF (the caller then
     * draws the static PNG). Decoded once and cached.
     */
    fun anim(species: Int, back: Boolean): AnimatedSprite? {
        val path = "anim/${if (back) "back" else "front"}/$species.gif"
        return animCache.getOrPut(path) {
            val bytes = runCatching { open(path).use { it.readBytes() } }.getOrNull()
            if (bytes == null || bytes.size < 6 || bytes[0] != 'G'.code.toByte()) null
            else runCatching { GifDecoder.decode(bytes) }.getOrNull()
        }
    }

    fun front(species: Int) = load("sprites/front/$species.png")
    fun back(species: Int) = load("sprites/back/$species.png")
    /** 32x64 icon sheet (two animation frames stacked) */
    fun icon(species: Int) = load("sprites/icon/$species.png")
    fun trainer(cell: Int) = load("sprites/trainer/$cell.png")
    fun battleBackground(name: String) = load("sprites/battle_bg/$name.png")
}
