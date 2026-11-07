package com.opicih.coach.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Runtime detection of speech features, shown in Settings > 음성 진단. */
object SpeechCapabilities {
    data class Report(
        val recognitionAvailable: Boolean,
        val onDeviceAvailable: Boolean?,
        val recognizerPackages: List<String>,
        val sdk: Int,
    )

    fun detect(context: Context): Report {
        val pm = context.packageManager
        val services = runCatching {
            pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.MATCH_ALL).map { it.serviceInfo.packageName }
        }.getOrDefault(emptyList())
        val onDevice = if (Build.VERSION.SDK_INT >= 31) runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrNull() else null
        return Report(SpeechRecognizer.isRecognitionAvailable(context), onDevice, services.distinct(), Build.VERSION.SDK_INT)
    }
}
