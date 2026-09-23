/*
 * Copyright 2026 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.call

import org.matrix.android.sdk.api.session.room.model.call.CallCandidate

/**
 * Strips real network IP addresses out of ICE candidate / SDP text before it's sent as a Matrix
 * call-signaling event, so the other call participant can't learn the user's real network
 * location. See MOBILITY-4768.
 *
 * `relay` (TURN) candidates keep their primary address — that's the TURN server's IP, not the
 * user's, and calls need it to connect through NAT/firewalls — but their `raddr` (the user's own
 * local address used to reach the TURN server) is still redacted, regardless of type.
 *
 * Parsing is positional against the ICE candidate-attribute grammar (RFC 5245 / RFC 8839):
 * candidate:foundation component transport priority address port typ type [...extensions]
 * - only the address-shaped tokens (the primary address and an optional `raddr` value) are ever
 * replaced; foundation/priority/port/every other extension token is left byte-for-byte identical.
 */
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
                line.startsWith("a=candidate:") -> "a=" + sanitizeCandidateLine(line.removePrefix("a="))
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
