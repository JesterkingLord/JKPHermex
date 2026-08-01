package com.hermexapp.android.features.chat

internal enum class VoiceRecognitionMode {
    ON_DEVICE,
    SYSTEM_DEFAULT,
    UNAVAILABLE,
}

internal enum class VoiceStartDecision {
    START,
    REQUEST_PERMISSION,
    UNAVAILABLE,
}

internal fun selectVoiceRecognitionMode(
    sdkInt: Int,
    onDeviceAvailable: Boolean,
    systemAvailable: Boolean,
): VoiceRecognitionMode = when {
    sdkInt >= 31 && onDeviceAvailable -> VoiceRecognitionMode.ON_DEVICE
    systemAvailable -> VoiceRecognitionMode.SYSTEM_DEFAULT
    else -> VoiceRecognitionMode.UNAVAILABLE
}

internal fun decideVoiceStart(
    recognizerAvailable: Boolean,
    hasPermission: Boolean,
): VoiceStartDecision = when {
    !recognizerAvailable -> VoiceStartDecision.UNAVAILABLE
    hasPermission -> VoiceStartDecision.START
    else -> VoiceStartDecision.REQUEST_PERMISSION
}
