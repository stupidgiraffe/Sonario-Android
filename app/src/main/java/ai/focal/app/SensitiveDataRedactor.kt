package ai.focal.app

/** Removes credential-shaped values before error text reaches UI, disk, or logs. */
object SensitiveDataRedactor {
    private const val REDACTED = "[REDACTED]"

    private val authorization = Regex(
        """(?i)(\bauthorization\s*[:=]\s*(?:bearer\s+)?)([^\s,;]+)"""
    )
    private val bearer = Regex("""(?i)(\bbearer\s+)([A-Za-z0-9._~+/=-]{8,})""")
    private val namedSecret = Regex(
        """(?i)(["']?(?:x-api-key|api[_-]?key|access[_-]?token|token)["']?\s*[:=]\s*["']?)([^"'\s,;}\]]+)"""
    )
    private val secretInQuery = Regex(
        """(?i)([?&](?:api[_-]?key|access[_-]?token|token|key)=)([^&#\s]+)"""
    )
    private val urlUserInfo = Regex("""(?i)(https?://)([^/@\s]+)@""")
    private val commonKey = Regex(
        """(?i)\b(?:sk-[A-Za-z0-9_-]{8,}|gsk_[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{16,})\b"""
    )

    fun redact(text: String, maxChars: Int = Int.MAX_VALUE): String {
        require(maxChars >= 0)
        var safe = text
        safe = authorization.replace(safe) { "${it.groupValues[1]}$REDACTED" }
        safe = bearer.replace(safe) { "${it.groupValues[1]}$REDACTED" }
        safe = namedSecret.replace(safe) { "${it.groupValues[1]}$REDACTED" }
        safe = secretInQuery.replace(safe) { "${it.groupValues[1]}$REDACTED" }
        safe = urlUserInfo.replace(safe) { "${it.groupValues[1]}$REDACTED@" }
        safe = commonKey.replace(safe, REDACTED)
        safe = safe.map { character ->
            if (!character.isISOControl() || character == '\n' || character == '\r' ||
                character == '\t'
            ) character else ' '
        }.joinToString("")
        return if (safe.length <= maxChars) safe else safe.take(maxChars) + "…"
    }
}
