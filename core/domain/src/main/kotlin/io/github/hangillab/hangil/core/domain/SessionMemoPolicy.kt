package io.github.hangillab.hangil.core.domain

object SessionMemoPolicy {
    const val MAX_CODE_POINTS = 280

    /** Count a valid surrogate pair once. Validation separately rejects isolated surrogates. */
    fun countCodePoints(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val paired = text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
            index += if (paired) 2 else 1
            count++
        }
        return count
    }

    fun isValidText(text: String): Boolean {
        if (text.isBlank() || countCodePoints(text) > MAX_CODE_POINTS) return false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character == '\u0000' -> return false
                character.isHighSurrogate() -> {
                    if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                character.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }
}
