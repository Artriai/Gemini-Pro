package com.rx.geminipro.utils

import com.rx.geminipro.utils.network.DownloadTransfer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadTransferTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun multipleChunksPreserveAllBytesAndCleanup() {
        val bytes = ByteArray(140_000) { (it % 251).toByte() }
        val download = DownloadTransfer(folder.root, bytes.size.toLong())
        bytes.asList().chunked(DownloadTransfer.CHUNK_BYTES).forEach { download.write(it.toByteArray()) }
        val file = download.finish()
        assertArrayEquals(bytes, file.readBytes())
        download.close()
        assertFalse(file.exists())
    }

    @Test fun truncatedTransfersCannotBePublished() {
        val download = DownloadTransfer(folder.root, 10)
        download.write(byteArrayOf(1, 2))
        assertThrows(IllegalStateException::class.java) { download.finish() }
        download.close()
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun oversizedPayloadsAndChunksAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadTransfer(folder.root, DownloadTransfer.MAX_BYTES + 1)
        }
        DownloadTransfer(folder.root, 2).use { download ->
            assertThrows(IllegalArgumentException::class.java) { download.write(byteArrayOf(1, 2, 3)) }
        }
        DownloadTransfer(folder.root, 100_000).use { download ->
            assertThrows(IllegalArgumentException::class.java) { download.write(ByteArray(DownloadTransfer.CHUNK_BYTES + 1)) }
        }
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
