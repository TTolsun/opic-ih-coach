package com.opicih.coach.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

data class ImportResult(val fileName: String, val mime: String, val rawText: String, val scripts: List<ParsedScript>)

/** UTF-8 byte-order mark that some Windows editors put at the start of text files. */
private val BOM = 0xFEFF.toChar().toString()

/** Reads TXT / MD / PDF / DOCX from a SAF Uri and parses scripts. Errors become Korean ImportException messages. */
class ImportService(private val context: Context) {

    suspend fun readUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val size = fileSize(uri)
        if (size != null && size > MAX_BYTES) throw ImportException("파일이 5MB를 넘습니다 (${size / 1024 / 1024}MB). 텍스트만 복사해서 붙여넣기를 사용하세요.")
        val mime = context.contentResolver.getType(uri) ?: guessMime(name)
        val text = when {
            mime == "application/pdf" || name.endsWith(".pdf", true) -> readPdf(uri)
            name.endsWith(".docx", true) || mime.contains("wordprocessingml") ->
                context.contentResolver.openInputStream(uri)?.use { DocxTextExtractor.extract(it) } ?: throw ImportException("파일을 열 수 없습니다.")
            name.endsWith(".doc", true) -> throw ImportException(".doc(구버전 Word)는 지원하지 않습니다. .docx 또는 PDF로 저장해서 가져오세요.")
            else -> readText(uri)
        }
        if (text.isBlank()) throw ImportException("파일에서 텍스트를 찾지 못했습니다. 이미지로 된 PDF라면 텍스트 복사 후 붙여넣기를 사용하세요.")
        ImportResult(name, mime, text, parseText(text, name))
    }

    suspend fun parseText(text: String, name: String = "붙여넣기"): List<ParsedScript> = withContext(Dispatchers.Default) {
        ScriptParser.parse(text, defaultTopic = "My Scripts", fallbackTitle = name.substringBeforeLast('.'))
    }

    private fun readText(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ImportException("파일을 열 수 없습니다.")
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // Korean Windows text files are often CP949/EUC-KR.
            String(bytes, charset("MS949"))
        }.removePrefix(BOM)
    }

    private fun readPdf(uri: Uri): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("파일을 열 수 없습니다.")
        return try {
            input.use { PDDocument.load(it).use { doc -> PDFTextStripper().getText(doc) } }
        } catch (e: InvalidPasswordException) {
            throw ImportException("암호가 걸린 PDF는 가져올 수 없습니다.")
        } catch (e: OutOfMemoryError) {
            throw ImportException("PDF가 너무 커서 처리할 수 없습니다.")
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("PDF를 읽지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"

    private fun fileSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun guessMime(name: String) = when {
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "text/plain"
    }

    companion object {
        const val MAX_BYTES = 5L * 1024 * 1024
        val MIME_TYPES = arrayOf(
            "text/plain", "text/markdown", "text/x-markdown", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream",
        )
    }
}
