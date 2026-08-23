package com.personal.smsforwarder.core

/**
 * Flattens a throwable into one readable line, cause chain included.
 *
 * `Throwable.message` alone is not enough: wrappers like `ExceptionInInitializerError`
 * and `InvocationTargetException` carry a null message, so the only useful information
 * is in the cause. This is what History and the test-send snackbar show, and it is
 * frequently the only diagnostic available for a failure on a real handset.
 */
fun Throwable.describe(maxDepth: Int = 5): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < maxDepth) {
        val name = current.javaClass.simpleName
        val message = current.message?.takeIf { it.isNotBlank() }
        parts += if (message != null) "$name: $message" else name
        if (current.cause === current) break
        current = current.cause
        depth++
    }
    return parts.joinToString(" <- caused by ")
}
