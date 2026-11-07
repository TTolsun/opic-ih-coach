package com.opicih.coach.importer

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extracts plain text from a .docx without extra libraries: reads word/document.xml, keeps paragraphs. */
object DocxTextExtractor {
    fun extract(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    return fromDocumentXml(zip.readBytes().toString(Charsets.UTF_8))
                }
                entry = zip.nextEntry
            }
        }
        throw ImportException("DOCX 안에서 본문(word/document.xml)을 찾지 못했습니다. 파일이 손상되었을 수 있습니다.")
    }

    fun fromDocumentXml(xml: String): String {
        val sb = StringBuilder()
        val token = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|</w:p>|<w:br\\s*/>|<w:tab\\s*/>")
        token.findAll(xml).forEach { m ->
            when {
                m.value.startsWith("</w:p") || m.value.startsWith("<w:br") -> sb.append('\n')
                m.value.startsWith("<w:tab") -> sb.append('\t')
                else -> sb.append(unescape(m.groupValues[1]))
            }
        }
        return sb.toString().trim()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}

class ImportException(message: String) : Exception(message)
