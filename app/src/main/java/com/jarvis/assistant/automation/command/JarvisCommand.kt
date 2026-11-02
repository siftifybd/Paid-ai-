package com.jarvis.assistant.automation.command

sealed class JarvisCommand {
    data class PlayYouTube(val query: String) : JarvisCommand()
    data class SearchYouTube(val query: String) : JarvisCommand()
    data class OpenApp(val packageName: String) : JarvisCommand()
    data class WhatsAppMessage(val contact: String, val message: String) : JarvisCommand()
    data class WhatsAppCall(val contact: String, val video: Boolean) : JarvisCommand()
    data class WhatsAppDeleteMessage(val contact: String?, val forEveryone: Boolean) : JarvisCommand()
    object SubscribeChannel : JarvisCommand()
    object ToggleFullscreen : JarvisCommand()
    data class Brightness(val level: Int, val isRelative: Boolean = false, val delta: Int = 0) : JarvisCommand()
    data class Volume(val level: Int, val isRelative: Boolean = false, val delta: Int = 0) : JarvisCommand()
    data class Flash(val enabled: Boolean) : JarvisCommand()
    data class Remember(val text: String) : JarvisCommand()
    data class Forget(val query: String) : JarvisCommand()
    object WhatDoYouRemember : JarvisCommand()
    data class SearchWeb(val query: String) : JarvisCommand()
    data class OpenWebUrls(val urls: List<String>) : JarvisCommand()
    data class PhoneCall(val contactOrNumber: String) : JarvisCommand()
    object PauseMedia : JarvisCommand()
    object ResumeMedia : JarvisCommand()
    object NextMedia : JarvisCommand()
    object PreviousMedia : JarvisCommand()
    data class ConfirmAction(val confirmed: Boolean) : JarvisCommand()
    data class DisambiguationChoice(val choice: String) : JarvisCommand()
    object RecallBirthday : JarvisCommand()
    object RecallFavoriteSong : JarvisCommand()
    object RecallRoutine : JarvisCommand()
    object GoToBackground : JarvisCommand()
    object LockPhone : JarvisCommand()
    object ShowUnlockScreen : JarvisCommand()

    fun isPremiumRequired(): Boolean {
        return when (this) {
            is Remember,
            is Forget,
            is WhatDoYouRemember,
            is RecallBirthday,
            is RecallFavoriteSong,
            is RecallRoutine,
            is GoToBackground -> false
            else -> true
        }
    }

    companion object {
        const val PREMIUM_LOCK_MESSAGE = "🔒 এই ফিচারটি ব্যবহারের জন্য JARVIS Premium লাইসেন্স প্রয়োজন। সম্পূর্ণ ফোন কন্ট্রোল, অ্যাপ ওপেন ও অটোমেশন আনলক করতে অনুগ্রহ করে প্রিমিয়াম লাইসেন্স সক্রিয় করুন।"
        const val PREMIUM_LOCK_SPOKEN = "এই ফিচারটি ব্যবহার করতে প্রিমিয়াম লাইসেন্স প্রয়োজন। সম্পূর্ণ ফোন কন্ট্রোল ও অটোমেশন আনলক করতে অনুগ্রহ করে প্রিমিয়াম লাইসেন্স সক্রিয় করুন।"
    }
}
