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

    fun sanitizeCandidate(candidate: CallCandidate): CallCandidate {
        val original = candidate.candidate ?: return candidate
        return candidate.copy(candidate = sanitizeCandidateLine(original))
    }

    fun sanitizeSdp(sdp: String): String {
        return sdp.lineSequence().joinToString("\r\n") { line ->
            when {
                line.startsWith("a=candidate:") -> sanitizeCandidateLine(line.removePrefix("a=")).let { if (it.isEmpty()) "" else "a=$it" }
                line.startsWith("c=IN IP4 ") || line.startsWith("c=IN IP6 ") -> sanitizeConnectionLine(line)
                else -> line
            }
        }
    }

    private fun sanitizeConnectionLine(line: String): String {
        val tokens = line.split(" ")
        if (tokens.size < 3) return line
        val placeholder = if (tokens[1] == "IP6") IPV6_PLACEHOLDER else IPV4_PLACEHOLDER
        return listOf(tokens[0], tokens[1], placeholder).joinToString(" ")
    }

    private fun sanitizeCandidateLine(line: String): String {
        val tokens = line.split(" ")
        val isWellFormed = tokens.size >= MIN_CANDIDATE_TOKENS &&
                tokens[0].startsWith("candidate:") &&
                tokens[CANDIDATE_TYP_KEYWORD_INDEX] == "typ"
        if (!isWellFormed) return ""

        val sanitizedTokens = tokens.toMutableList()
        if (sanitizedTokens[CANDIDATE_TYPE_INDEX] != RELAY_TYPE) {
            sanitizedTokens[CANDIDATE_ADDRESS_INDEX] = placeholderFor(sanitizedTokens[CANDIDATE_ADDRESS_INDEX])
        }
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
