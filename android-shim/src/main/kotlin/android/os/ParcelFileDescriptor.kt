package android.os

import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/**
 * Stand-in for android.os.ParcelFileDescriptor.
 *
 * The extensions that serve chapters as PDFs open the cached file with [open] and hand the
 * descriptor to [android.graphics.pdf.PdfRenderer], which is its only consumer in the sweep.
 * Android's renderer needs a seekable descriptor, so the stand-in holds a [RandomAccessFile]
 * open for it and [close] closes it; the read-only mode is the only one asked for.
 */
class ParcelFileDescriptor private constructor(internal val file: File) : Closeable {

    internal val handle: RandomAccessFile = RandomAccessFile(file, "r")

    override fun close() {
        handle.close()
    }

    companion object {
        const val MODE_READ_ONLY: Int = 0x00000000

        @JvmStatic
        @Throws(FileNotFoundException::class)
        fun open(file: File, mode: Int): ParcelFileDescriptor = ParcelFileDescriptor(file)
    }
}
