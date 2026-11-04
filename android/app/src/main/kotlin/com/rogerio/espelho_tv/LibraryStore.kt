
package com.rogerio.espelho_tv

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object LibraryStore {

    private const val FOLDER_NAME = "library"

    /**
     * Diretório interno da biblioteca de filmes.
     */
    fun dir(context: Context): File {
        val folder = File(context.filesDir, FOLDER_NAME)

        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException(
                "Não foi possível criar a biblioteca de filmes."
            )
        }

        return folder
    }

    /**
     * Lista os filmes armazenados.
     */
    fun list(context: Context): List<File> {
        return dir(context)
            .listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()
    }

    /**
     * Localiza um arquivo pelo nome.
     */
    fun find(context: Context, name: String): File? {
        if (name.isBlank() || name.contains("/") || name.contains("\\")) {
            return null
        }

        val folder = dir(context).canonicalFile
        val file = File(folder, name).canonicalFile

        if (file.parentFile != folder) return null

        return file.takeIf { it.isFile && it.name == name }
    }

    /**
     * Obtém nome e tamanho do vídeo selecionado.
     */
    fun displayNameAndSize(
        context: Context,
        uri: Uri
    ): Pair<String, Long> {

        var name: String? = null
        var size = -1L

        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {

                val nameIndex =
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                val sizeIndex =
                    cursor.getColumnIndex(OpenableColumns.SIZE)

                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }

                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }

        val safeName = sanitizeName(
            name ?: uri.lastPathSegment ?: "video_${System.currentTimeMillis()}.mp4"
        )

        return Pair(safeName, size)
    }

    /**
     * Importa um vídeo para a biblioteca interna.
     * Informa o progresso em bytes copiados e tamanho total.
     */
    fun importOne(
        context: Context,
        uri: Uri,
        onProgress: (Long, Long) -> Unit
    ): File {

        val folder = dir(context)
        val (originalName, totalSize) = displayNameAndSize(context, uri)

        val tempFile = File.createTempFile(
            "import_",
            ".part",
            folder
        )

        try {

            var copied = 0L

            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException(
                    "Não foi possível abrir o vídeo selecionado."
                )

            input.use { source ->

                FileOutputStream(tempFile).use { output ->

                    val buffer = ByteArray(64 * 1024)

                    while (true) {

                        val count = source.read(buffer)

                        if (count < 0) break

                        output.write(buffer, 0, count)

                        copied += count

                        onProgress(copied, totalSize)
                    }

                    output.flush()
                }
            }

            if (copied <= 0L) {
                throw IllegalStateException("O vídeo está vazio.")
            }

            val destination = uniqueFile(folder, originalName)

            if (!tempFile.renameTo(destination)) {

                tempFile.copyTo(destination, overwrite = false)

                if (!tempFile.delete()) {
                    tempFile.deleteOnExit()
                }
            }

            return destination

        } catch (e: Exception) {

            tempFile.delete()

            throw e
        }
    }

    /**
     * Evita sobrescrever vídeos com o mesmo nome.
     */
    private fun uniqueFile(folder: File, originalName: String): File {

        val dot = originalName.lastIndexOf('.')

        val baseName =
            if (dot > 0) originalName.substring(0, dot)
            else originalName

        val extension =
            if (dot > 0) originalName.substring(dot)
            else ""

        var candidate = File(folder, originalName)
        var counter = 1

        while (candidate.exists()) {

            candidate = File(
                folder,
                "${baseName}_$counter$extension"
            )

            counter++
        }

        return candidate
    }

    /**
     * Remove caracteres que podem comprometer o nome do arquivo.
     */
    private fun sanitizeName(name: String): String {

        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trimStart('.')

        return cleaned.ifBlank {
            "video_${System.currentTimeMillis()}.mp4"
        }
    }

    /**
     * Identifica o tipo MIME do vídeo.
     */
    fun mimeOf(file: File): String {

        val extension = file.extension.lowercase(Locale.ROOT)

        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}