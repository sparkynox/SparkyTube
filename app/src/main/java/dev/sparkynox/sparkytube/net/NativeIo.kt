package dev.sparkynox.sparkytube.net

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

// file writes for the downloader: reserve the space once, then every thread writes its own part.
// the native version uses fallocate + pwrite, if the library isn't there it falls back to RandomAccessFile
object NativeIo {

    private val loaded = try {
        System.loadLibrary("sparkynet")
        true
    } catch (_: Throwable) {
        false
    }

    private external fun nativeOpen(path: String, size: Long): Int
    private external fun nativeWrite(fd: Int, buf: ByteArray, len: Int, off: Long): Int
    private external fun nativeSync(fd: Int): Int
    private external fun nativeClose(fd: Int)

    class Sink internal constructor(private val fd: Int, private val raf: RandomAccessFile?) {
        fun write(buf: ByteArray, len: Int, off: Long) {
            if (raf != null) {
                synchronized(raf) {
                    raf.seek(off)
                    raf.write(buf, 0, len)
                }
            } else {
                val r = nativeWrite(fd, buf, len, off)
                if (r < 0) throw IOException("write failed (errno ${-r})")
            }
        }

        fun sync() {
            if (raf != null) raf.fd.sync() else nativeSync(fd)
        }

        fun close() {
            if (raf != null) raf.close() else nativeClose(fd)
        }
    }

    fun open(file: File, size: Long): Sink {
        if (loaded) {
            val fd = nativeOpen(file.absolutePath, size)
            if (fd >= 0) return Sink(fd, null)
            if (fd == -28) throw IOException("Not enough storage space")   // ENOSPC
        }
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(size)
        return Sink(-1, raf)
    }
}
