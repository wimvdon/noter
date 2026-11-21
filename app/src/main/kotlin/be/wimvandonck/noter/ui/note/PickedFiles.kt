package be.wimvandonck.noter.ui.note

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import be.wimvandonck.noter.logic.FileUploads
import be.wimvandonck.noter.logic.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** What reading a picked photo or file gave. */
sealed interface ReadFile {
    data class Read(val file: PickedFile) : ReadFile

    /** Bigger than [FileUploads.SINGLE_PART_MAX_BYTES]: not read at all. */
    data object TooLarge : ReadFile

    data object Failed : ReadFile
}

/**
 * Reads a photo or file the user picked (the system photo picker or file picker hands over a
 * link to it, no permission needed) into memory, to send to Notion. Nothing is kept on the phone.
 */
class PickedFiles(private val context: Context) {
    suspend fun read(uri: Uri, maxBytes: Long = FileUploads.SINGLE_PART_MAX_BYTES): ReadFile = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            var name: String? = null
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
            if ((size ?: 0) > maxBytes) return@withContext ReadFile.TooLarge
            val mimeType = resolver.getType(uri) ?: DEFAULT_TYPE
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    // The size the picker gave can be missing or wrong: stop reading past the limit.
                    if (total > maxBytes) return@withContext ReadFile.TooLarge
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: return@withContext ReadFile.Failed
            if (bytes.isEmpty()) return@withContext ReadFile.Failed
            ReadFile.Read(PickedFile(FileUploads.fileName(name, mimeType), mimeType, bytes))
        } catch (_: IOException) {
            ReadFile.Failed
        } catch (_: SecurityException) {
            ReadFile.Failed
        }
    }

    private companion object {
        const val DEFAULT_TYPE = "application/octet-stream"
        const val BUFFER = 64 * 1024
    }
}
