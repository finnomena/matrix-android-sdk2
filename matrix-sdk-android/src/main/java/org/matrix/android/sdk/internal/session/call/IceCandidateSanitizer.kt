package org.matrix.android.sdk.internal.session.call

import org.matrix.android.sdk.api.session.room.model.call.CallCandidate
internal object IceCandidateSanitizer {

    private const val IPV4_PLACEHOLDER = "0.0.0.0"
    private const val IPV6_PLACEHOLDER = "::"
    private const val RELAY_TYPE = "relay"
    private const val CANDIDATE_ADDRESS_INDEX = 4
    private const val CANDIDATE_TYP_KEYWORD_INDEX = 6
    private const val CANDIDATE_TYPE_INDEX = 7
    private const val MIN_CANDIDATE_TOKENS = 8

    /**
     * Returns the candidate with its `raddr` redacted if it's a `relay` candidate (its primary
     * address is the TURN server's, not the user's, safe to send), or null if it's a non-relay
     * candidate (host/srflx/prflx) or malformed — those must be dropped entirely, not sent with a
     * zeroed primary address: keeping a redacted-but-present candidate in the list still lets the
     * ICE agent spend its connectivity-check budget on pairs that can never succeed, prioritized
     * ahead of relay pairs by the ICE priority formula, which can starve out the one pair that
     * would actually work. See MOBILITY-4768.
     */
    fun sanitizeCandidate(candidate: CallCandidate): CallCandidate? {
        val sanitizedLine = candidate.candidate?.let(::sanitizeCandidateLine) ?: return null
        return candidate.copy(candidate = sanitizedLine)
    }

    fun sanitizeSdp(sdp: String): String {
        return sdp.lineSequence()
                .mapNotNull { line ->
                    when {
                        line.startsWith("a=candidate:") -> sanitizeCandidateLine(line.removePrefix("a="))?.let { "a=$it" }
                        line.startsWith("c=IN IP4 ") || line.startsWith("c=IN IP6 ") -> sanitizeConnectionLine(line)
                        else -> line
                    }
                }
                .joinToString("\r\n")
    }

    private fun sanitizeConnectionLine(line: String): String {
        val tokens = line.split(" ")
        if (tokens.size < 3) return line
        val placeholder = if (tokens[1] == "IP6") IPV6_PLACEHOLDER else IPV4_PLACEHOLDER
        return listOf(tokens[0], tokens[1], placeholder).joinToString(" ")
    }

    /**
     * Returns the line with only its `raddr` redacted if it's a `relay` candidate, or null if it's
     * a non-relay candidate or doesn't match the expected grammar — both cases must be dropped
     * entirely rather than kept with a zeroed primary address (see [sanitizeCandidate]).
     */
    private fun sanitizeCandidateLine(line: String): String? {
        val tokens = line.split(" ")
        val isWellFormed = tokens.size >= MIN_CANDIDATE_TOKENS &&
                tokens[0].startsWith("candidate:") &&
                tokens[CANDIDATE_TYP_KEYWORD_INDEX] == "typ"
        if (!isWellFormed || tokens[CANDIDATE_TYPE_INDEX] != RELAY_TYPE) return null

        val sanitizedTokens = tokens.toMutableList()
        val raddrIndex = sanitizedTokens.indexOf("raddr")
        if (raddrIndex != -1 && raddrIndex + 1 < sanitizedTokens.size) {
            sanitizedTokens[raddrIndex + 1] = placeholderFor(sanitizedTokens[raddrIndex + 1])
        }
        return sanitizedTokens.joinToString(" ")
    }

    private fun placeholderFor(address: String): String {
        return if (address.contains(":")) IPV6_PLACEHOLDER else IPV4_PLACEHOLDER
    }
}
