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
 * Blanks the `raddr`/`rport` fields on outbound ICE candidate / SDP text before it's sent as a
 * Matrix call-signaling event, so the other call participant can't learn the user's real network
 * address.
 *
 * The primary candidate address (`candidate:... <address> <port> typ <type>`) is left untouched for
 * `relay`/`srflx` candidates, since it's required for ICE connectivity checks; `raddr`/`rport` are
 * purely informational per RFC 5245 §15.1 and safe to blank.
 *
 * `host` candidates are dropped outright rather than blanked, because their primary address is the
 * device's own network address (private on IPv4, often a real publicly routable address on IPv6),
 * with no `raddr`/`rport` field to redact instead.
 */
internal object IceCandidateSanitizer {

    private const val IPV4_PLACEHOLDER = "0.0.0.0"
    private const val IPV6_PLACEHOLDER = "::"
    private const val RPORT_PLACEHOLDER = "9"

    /**
     * Returns the sanitized candidate, or `null` if it's a `host` candidate and should be dropped
     * from the outbound list entirely.
     */
    fun sanitizeCandidate(candidate: CallCandidate): CallCandidate? {
        val original = candidate.candidate ?: return candidate
        if (isHostCandidateLine(original)) return null
        return candidate.copy(candidate = sanitizeCandidateLine(original))
    }

    fun sanitizeSdp(sdp: String): String {
        return sdp.lineSequence().mapNotNull { line ->
            when {
                line.startsWith("a=candidate:") -> sanitizeSdpCandidateLine(line)
                line.startsWith("c=IN IP4 ") || line.startsWith("c=IN IP6 ") -> sanitizeConnectionLine(line)
                else -> line
            }
        }.joinToString("\r\n")
    }

    /** Returns `null` if the line is a `host` candidate and should be dropped from the SDP. */
    private fun sanitizeSdpCandidateLine(line: String): String? {
        val candidateLine = line.removePrefix("a=")
        if (isHostCandidateLine(candidateLine)) return null
        return "a=" + sanitizeCandidateLine(candidateLine)
    }

    private fun isHostCandidateLine(line: String): Boolean {
        val tokens = line.split(" ")
        val typIndex = tokens.indexOf("typ")
        return typIndex != -1 && typIndex + 1 < tokens.size && tokens[typIndex + 1] == "host"
    }

    private fun sanitizeConnectionLine(line: String): String {
        val tokens = line.split(" ")
        if (tokens.size < 3) return line
        val placeholder = if (tokens[1] == "IP6") IPV6_PLACEHOLDER else IPV4_PLACEHOLDER
        return listOf(tokens[0], tokens[1], placeholder).joinToString(" ")
    }

    /**
     * Finds `raddr`/`rport` by exact token match (not by position), so this works regardless of
     * whether the rest of the line matches the full candidate-attribute grammar, and leaves a line
     * with neither field completely unchanged. Only called for `relay`/`srflx` lines — `host` lines
     * are filtered out by the caller before reaching this function.
     */
    private fun sanitizeCandidateLine(line: String): String {
        val tokens = line.split(" ").toMutableList()
        val raddrIndex = tokens.indexOf("raddr")
        if (raddrIndex != -1 && raddrIndex + 1 < tokens.size) {
            tokens[raddrIndex + 1] = placeholderFor(tokens[raddrIndex + 1])
        }
        val rportIndex = tokens.indexOf("rport")
        if (rportIndex != -1 && rportIndex + 1 < tokens.size) {
            tokens[rportIndex + 1] = RPORT_PLACEHOLDER
        }
        return tokens.joinToString(" ")
    }

    private fun placeholderFor(address: String): String {
        return if (address.contains(":")) IPV6_PLACEHOLDER else IPV4_PLACEHOLDER
    }
}
