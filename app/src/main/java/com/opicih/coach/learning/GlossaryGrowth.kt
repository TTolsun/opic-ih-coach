package com.opicih.coach.learning

/**
 * Validates glossary entries the AI proposed together with improved script versions. The model may invent forms that
 * are not in the text or repeat words the glossary already has, so only forms that really occur in a generated version,
 * are new to the glossary and were not already written by the learner are kept.
 */
object GlossaryGrowth {
    const val MAX_ENTRIES = 12

    fun accept(candidates: List<GlossaryEntry>, existing: Glossary, generated: List<String>, original: String): List<GlossaryEntry> {
        val generatedKeys = generated.map(::tokenKeys)
        val originalKeys = tokenKeys(original)
        val seen = HashSet<String>()
        val out = ArrayList<GlossaryEntry>()
        for (c in candidates) {
            if (out.size >= MAX_ENTRIES) break
            val phrase = c.phrase.trim()
            val meaning = c.meaningKo.trim()
            if (phrase.isBlank() || meaning.isBlank() || !seen.add(phrase.lowercase())) continue
            // A known headword keeps its meaning: an AI entry under the same phrase would shadow it in Glossary.entry().
            if (existing.entry(phrase) != null) continue
            val forms = (c.forms.map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf(phrase) })
                .distinctBy { it.lowercase() }
                .filter { f -> tokenKeys(f).let { k -> k.isNotEmpty() && generatedKeys.any { containsRun(it, k) } } }
                .filterNot(existing::knows)
            if (forms.isEmpty()) continue
            if (forms.all { containsRun(originalKeys, tokenKeys(it)) }) continue
            val kind = when (c.kind.trim().uppercase()) {
                "IDIOM" -> "IDIOM"
                "WORD" -> "WORD"
                else -> if (Glossary.tokenize(phrase).size > 1) "IDIOM" else "WORD"
            }
            out += GlossaryEntry(phrase, forms, kind, meaning, c.noteKo?.trim()?.ifBlank { null }, c.easier?.trim()?.ifBlank { null })
        }
        return out
    }

    private fun tokenKeys(text: String): List<String> = Glossary.tokenize(text).map { it.norm }

    private fun containsRun(text: List<String>, run: List<String>): Boolean {
        if (run.isEmpty() || run.size > text.size) return false
        for (i in 0..text.size - run.size) if (run.indices.all { text[i + it] == run[it] }) return true
        return false
    }
}
