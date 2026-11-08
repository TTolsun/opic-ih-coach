package com.opicih.coach.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.opicih.coach.data.db.WordLookupEntity
import com.opicih.coach.learning.GlossaryLookup
import com.opicih.coach.ui.LocalContainer
import com.opicih.coach.ui.theme.EnglishReading
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Script text where every word can be tapped for its meaning in this script. Idioms (meanings that the single words
 * do not give away) get a light tint so the learner sees which parts to learn as one unit.
 */
@Composable
fun GlossaryText(text: String, scriptId: Long, modifier: Modifier = Modifier, style: TextStyle = EnglishReading) {
    val c = LocalContainer.current
    val glossary = c.glossary.glossary
    val spans = remember(text) { glossary.spans(text) }
    var picked by remember(text) { mutableStateOf<GlossaryLookup?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val idiomBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val pickedBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    val annotated = remember(text, spans, picked, idiomBg, pickedBg) {
        buildAnnotatedString {
            append(text)
            spans.filter { it.entry.isIdiom }.forEach { addStyle(SpanStyle(background = idiomBg), it.start, it.end) }
            picked?.let { addStyle(SpanStyle(background = pickedBg), it.start, it.end) }
        }
    }
    Text(
        annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(text, spans) {
            detectTapGestures { pos -> layout?.let { picked = glossary.lookupAt(text, it.getOffsetForPosition(pos), spans) } }
        },
    )
    picked?.let { LookupSheet(it, scriptId, record = true) { picked = null } }
}

/** Phrases this script's reader looked up, most repeated first. Tapping one reopens its meaning. */
@Composable
fun LookedUpList(scriptId: Long) {
    val c = LocalContainer.current
    val lookups by c.glossary.lookups(scriptId).collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<WordLookupEntity?>(null) }
    if (lookups.isEmpty()) return
    SectionCard(title = "찾아본 표현") {
        lookups.take(8).forEach { l ->
            val entry = c.glossary.glossary.entry(l.phrase)
            Row(Modifier.fillMaxWidth().clickable { open = l }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.surface, style = MaterialTheme.typography.bodyLarge)
                    entry?.let { Text(it.meaningKo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (l.count > 1) SourceTag("${l.count}회", MaterialTheme.colorScheme.primary)
            }
        }
    }
    open?.let { l ->
        val lookup = GlossaryLookup(c.glossary.glossary.entry(l.phrase), null, l.surface, l.sentence, 0, 0)
        LookupSheet(lookup, scriptId, record = false) { open = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookupSheet(lookup: GlossaryLookup, scriptId: Long, record: Boolean, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val entry = lookup.entry
    var inReview by remember(lookup) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(lookup) {
        // NonCancellable: closing the sheet right away must not leave a half-written lookup.
        if (entry != null) inReview = withContext(NonCancellable) {
            if (record) c.glossary.record(scriptId, entry, lookup.surface, lookup.sentence) else c.glossary.isInReview(entry, lookup.surface)
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry?.phrase ?: lookup.surface, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (entry != null) SourceTag(if (entry.isIdiom) "숙어" else "단어", if (entry.isIdiom) primary else muted)
            }
            if (entry == null) {
                Text("등록된 뜻이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = muted)
            } else {
                Text(entry.meaningKo, style = MaterialTheme.typography.titleMedium)
                entry.noteKo?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
                entry.easier?.let {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)) { append("쉽게  ") }
                        append(it)
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            lookup.wordEntry?.let { w ->
                HorizontalDivider()
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(w.phrase) }
                    append("  ")
                    withStyle(SpanStyle(color = muted)) { append(w.meaningKo) }
                }, style = MaterialTheme.typography.bodyLarge)
            }
            if (lookup.sentence.isNotBlank() && lookup.sentence != lookup.surface) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(highlight(lookup.sentence, lookup.surface, primary), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(12.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { c.tts.speak(lookup.surface, 0.75f) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("발음")
                }
                Spacer(Modifier.width(8.dp))
                if (lookup.sentence.isNotBlank() && lookup.sentence != lookup.surface) {
                    OutlinedButton(onClick = { c.tts.speak(lookup.sentence, 0.9f) }) { Text("문장") }
                }
                Spacer(Modifier.weight(1f))
                when (inReview) {
                    true -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = primary); Spacer(Modifier.width(4.dp))
                        Text("복습 중", style = MaterialTheme.typography.labelLarge, color = primary)
                    }
                    false -> TextButton(onClick = {
                        scope.launch {
                            entry?.let { withContext(NonCancellable) { c.glossary.addToReview(it, lookup.surface, lookup.sentence) } }
                            inReview = true
                        }
                    }) { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("복습 추가") }
                    null -> Unit
                }
            }
        }
    }
}

private fun highlight(sentence: String, part: String, color: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    val i = sentence.indexOf(part, ignoreCase = true)
    if (i < 0 || part.isBlank()) { append(sentence); return@buildAnnotatedString }
    append(sentence.substring(0, i))
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(sentence.substring(i, i + part.length)) }
    append(sentence.substring(i + part.length))
}
