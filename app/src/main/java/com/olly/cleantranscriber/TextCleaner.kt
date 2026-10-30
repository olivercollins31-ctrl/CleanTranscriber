package com.olly.cleantranscriber

object TextCleaner {
    private val filler = Regex(
        "\\b(?:u+m+|u+h+|e+r+m*|a+h+|a+r+gh+|h+m+|m+h?m+)\\b[,.]?\\s*",
        RegexOption.IGNORE_CASE
    )
    private val stutter = Regex("\\b(\\w+)(\\s+\\1\\b)+", RegexOption.IGNORE_CASE)
    private val spaceBeforePunct = Regex("\\s+([,.?!])")
    private val multiSpace = Regex("\\s{2,}")
    private val soloI = Regex("\\bi\\b")
    private val opener = Regex(
        "^(okay|ok|yes|honestly|actually|basically|anyway)\\s+(?=\\w)",
        RegexOption.IGNORE_CASE
    )
    private val question = Regex(
        "^(who|what|when|where|why|how|which|whose|is|are|am|was|were|do|does|did|" +
            "can|could|will|would|should|shall|have|has|had|may|might)\\b",
        RegexOption.IGNORE_CASE
    )

    fun stripFillers(input: String): String {
        var t = filler.replace(input, " ")
        t = stutter.replace(t, "\$1")
        t = spaceBeforePunct.replace(t, "\$1")
        t = multiSpace.replace(t, " ")
        return t.trim()
    }

    fun punctuate(input: String, final: Boolean): String {
        var t = input.trim()
        if (t.isEmpty()) return t
        t = soloI.replace(t, "I")
        t = opener.replace(t) { m -> m.groupValues[1] + ", " }
        t = t.replaceFirstChar { it.uppercase() }
        if (final && t.last() !in ".?!") {
            t += if (question.containsMatchIn(t)) "?" else "."
        }
        return t
    }

    fun clean(raw: String, fillers: Boolean, punctuation: Boolean, final: Boolean = true): String {
        var t = raw
        if (fillers) t = stripFillers(t)
        t = t.trim()
        if (punctuation) t = punctuate(t, final)
        return t
    }
}
