package com.usumfactory.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * A small, dependency-free GIF89a decoder that produces fully-composited ARGB frames plus per-frame
 * delays, so the battle Canvas can draw the current frame of a Showdown gen5ani sprite while keeping
 * all of its own effects (squash/stretch, lunge, shadow, hit-flash). Handles global/local palettes,
 * transparency, interlacing, and the three frame disposal methods.
 *
 * This runs once per sprite (results are cached by Sprites), so clarity is favoured over micro-speed.
 */
object GifDecoder {

    fun decode(data: ByteArray): AnimatedSprite? {
        val r = Reader(data)
        if (r.readString(6) !in setOf("GIF87a", "GIF89a")) return null
        val width = r.readShort()
        val height = r.readShort()
        val packed = r.readByte()
        val gctFlag = packed and 0x80 != 0
        val gctSize = 2 shl (packed and 0x07)
        r.readByte() // background color index
        r.readByte() // pixel aspect ratio
        val gct = if (gctFlag) r.readColorTable(gctSize) else IntArray(0)

        val frames = ArrayList<ImageBitmap>()
        val delays = ArrayList<Int>()

        // Persistent canvas (ARGB) composited across frames per the disposal method.
        val canvas = IntArray(width * height) // starts transparent (0)

        var delayMs = 100
        var transparentIndex = -1
        var disposal = 0

        while (true) {
            when (r.readByte()) {
                0x2C -> { // Image Descriptor
                    val ix = r.readShort(); val iy = r.readShort()
                    val iw = r.readShort(); val ih = r.readShort()
                    val ipacked = r.readByte()
                    val lctFlag = ipacked and 0x80 != 0
                    val interlace = ipacked and 0x40 != 0
                    val lctSize = 2 shl (ipacked and 0x07)
                    val palette = if (lctFlag) r.readColorTable(lctSize) else gct
                    if (palette.isEmpty()) return null

                    val indices = r.readImageData(iw * ih)

                    // If the previous frame asked to be restored-to-previous, snapshot before drawing.
                    val snapshot = if (disposal == 3) canvas.copyOf() else null

                    // Composite this frame's pixels onto the canvas.
                    var p = 0
                    for (row in 0 until ih) {
                        val dy = iy + destRow(row, ih, interlace)
                        if (dy < 0 || dy >= height) { p += iw; continue }
                        var base = dy * width
                        for (col in 0 until iw) {
                            val idx = indices[p++].toInt() and 0xFF
                            val dx = ix + col
                            if (dx in 0 until width && idx != transparentIndex && idx < palette.size) {
                                canvas[base + dx] = palette[idx]
                            }
                        }
                    }

                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.setPixels(canvas, 0, width, 0, 0, width, height)
                    frames.add(bmp.asImageBitmap())
                    delays.add(delayMs)

                    // Apply disposal for the NEXT frame.
                    when (disposal) {
                        2 -> { // restore to background: clear this frame's rect to transparent
                            for (row in 0 until ih) {
                                val dy = iy + row
                                if (dy !in 0 until height) continue
                                val b = dy * width
                                for (col in 0 until iw) {
                                    val dx = ix + col
                                    if (dx in 0 until width) canvas[b + dx] = 0
                                }
                            }
                        }
                        3 -> snapshot?.copyInto(canvas) // restore to previous
                        // 0/1: leave the canvas as-is
                    }
                    // reset per-frame GCE state
                    transparentIndex = -1
                    disposal = 0
                }
                0x21 -> { // Extension
                    when (r.readByte()) {
                        0xF9 -> { // Graphic Control Extension
                            r.readByte() // block size (4)
                            val flags = r.readByte()
                            disposal = (flags shr 2) and 0x07
                            val delayCs = r.readShort() // in 1/100 s
                            val tIdx = r.readByte()
                            transparentIndex = if (flags and 0x01 != 0) tIdx else -1
                            delayMs = if (delayCs <= 0) 100 else delayCs * 10
                            r.skipBlocks()
                        }
                        else -> r.skipBlocks()
                    }
                }
                0x3B -> break // trailer
                else -> break // unexpected
            }
            if (r.eof()) break
        }
        if (frames.isEmpty()) return null
        return AnimatedSprite(frames, delays)
    }

    /** Interlaced GIFs store rows in four passes; map a decode-row to its destination row. */
    private fun destRow(row: Int, height: Int, interlace: Boolean): Int {
        if (!interlace) return row
        var r = row
        val p1 = (height + 7) / 8
        val p2 = (height + 3) / 8
        val p3 = (height + 1) / 4
        if (r < p1) return r * 8
        r -= p1
        if (r < p2) return r * 8 + 4
        r -= p2
        if (r < p3) return r * 4 + 2
        r -= p3
        return r * 4 + 1
    }

    private class Reader(val d: ByteArray) {
        var pos = 0
        fun eof() = pos >= d.size
        fun readByte(): Int = d[pos++].toInt() and 0xFF
        fun readShort(): Int { val v = (d[pos].toInt() and 0xFF) or ((d[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
        fun readString(n: Int): String { val s = String(d, pos, n, Charsets.US_ASCII); pos += n; return s }
        fun readColorTable(n: Int): IntArray {
            val t = IntArray(n)
            for (i in 0 until n) {
                val rr = d[pos].toInt() and 0xFF
                val gg = d[pos + 1].toInt() and 0xFF
                val bb = d[pos + 2].toInt() and 0xFF
                t[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                pos += 3
            }
            return t
        }
        fun skipBlocks() { while (true) { val n = readByte(); if (n == 0) break; pos += n } }

        /** LZW-decode the image data sub-blocks into [pixelCount] palette indices. */
        fun readImageData(pixelCount: Int): ByteArray {
            val minCodeSize = readByte()
            // gather sub-blocks
            val data = ArrayList<Byte>()
            while (true) {
                val n = readByte()
                if (n == 0) break
                for (i in 0 until n) data.add(d[pos + i])
                pos += n
            }
            return lzwDecode(data, minCodeSize, pixelCount)
        }
    }

    private fun lzwDecode(input: List<Byte>, minCodeSize: Int, pixelCount: Int): ByteArray {
        val out = ByteArray(pixelCount)
        var outPos = 0
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var next = endCode + 1
        // dictionary: each entry is a sequence of bytes
        val dict = ArrayList<ByteArray>(4096)
        fun resetDict() {
            dict.clear()
            for (i in 0 until clearCode) dict.add(byteArrayOf(i.toByte()))
            dict.add(ByteArray(0)) // clear
            dict.add(ByteArray(0)) // end
            codeSize = minCodeSize + 1
            next = endCode + 1
        }
        resetDict()

        var bitBuffer = 0
        var bitCount = 0
        var dataPos = 0
        var prev: ByteArray? = null

        fun nextCode(): Int {
            while (bitCount < codeSize) {
                if (dataPos >= input.size) return -1
                bitBuffer = bitBuffer or ((input[dataPos].toInt() and 0xFF) shl bitCount)
                bitCount += 8
                dataPos++
            }
            val code = bitBuffer and ((1 shl codeSize) - 1)
            bitBuffer = bitBuffer ushr codeSize
            bitCount -= codeSize
            return code
        }

        while (true) {
            val code = nextCode()
            if (code < 0 || code == endCode) break
            if (code == clearCode) { resetDict(); prev = null; continue }
            val entry: ByteArray = when {
                code < dict.size -> dict[code]
                prev != null -> prev!! + prev!![0]   // KwKwK case
                else -> break
            }
            var i = 0
            while (i < entry.size && outPos < pixelCount) { out[outPos++] = entry[i]; i++ }
            if (prev != null && next < 4096) {
                dict.add(prev!! + entry[0])
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            prev = entry
            if (outPos >= pixelCount) break
        }
        return out
    }
}
