package icu.nullptr.polyglot.translate

import java.net.HttpURLConnection

interface RequestControl {
    fun tryBeginAttempt(): Boolean = true

    fun isCancelled(): Boolean = false

    fun onConnection(connection: HttpURLConnection?) {}

    fun onRequestBodySent() {}

    fun onProvisional(index: Int, translated: String) {}
}
