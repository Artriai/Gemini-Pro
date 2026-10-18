package com.rx.geminipro.utils.network

import java.io.File
import java.io.FileOutputStream

/** Disk-backed state for one download. All methods run on the download worker. */
class DownloadTransfer(directory: File, private val expectedBytes: Long) : AutoCloseable {
    private val file: File
    private var output: FileOutputStream?
    private var writtenBytes = 0L

    init {
        require(expectedBytes in 0..MAX_BYTES)
        file = File.createTempFile("blob_", ".part", directory)
        output = FileOutputStream(file)
    }

    fun write(bytes: ByteArray) {
        require(bytes.size <= CHUNK_BYTES)
        require(writtenBytes + bytes.size <= expectedBytes)
        checkNotNull(output).write(bytes)
        writtenBytes += bytes.size
    }

    fun finish(): File {
        check(writtenBytes == expectedBytes)
        checkNotNull(output).close()
        output = null
        return file
    }

    override fun close() {
        runCatching { output?.close() }
        output = null
        file.delete()
    }

    companion object {
        const val CHUNK_BYTES = 49_152
        const val MAX_BYTES = 256L * 1024 * 1024
    }
}
