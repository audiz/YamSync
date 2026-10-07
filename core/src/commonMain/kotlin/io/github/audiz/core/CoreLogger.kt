package io.github.audiz.core

object CoreLogger {
    var listener: ((level: String, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    fun d(tag: String, message: String) {
        listener?.invoke("DEBUG", tag, message, null) ?: println("DEBUG [$tag] $message")
    }

    fun i(tag: String, message: String) {
        listener?.invoke("INFO", tag, message, null) ?: println("INFO [$tag] $message")
    }

    fun w(tag: String, message: String) {
        listener?.invoke("WARN", tag, message, null) ?: println("WARN [$tag] $message")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        listener?.invoke("ERROR", tag, message, throwable) ?: run {
            println("ERROR [$tag] $message")
            throwable?.printStackTrace()
        }
    }
}
