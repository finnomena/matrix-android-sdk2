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

internal object IceCandidateSanitizer {
    private const val IPV4_PLACEHOLDER = "0.0.0.0"
    private const val IPV6_PLACEHOLDER = "::"
    private const val RPORT_PLACEHOLDER = "9"

    private const val CONNECTION_ADDRESS_INDEX = 4
    private const val CONNECTION_PORT_INDEX = 5

    fun sanitizeCandidate(candidate: CallCandidate): CallCandidate {
        val original = candidate.candidate ?: return candidate
        return candidate.copy(candidate = sanitizeCandidateLine(original))
    }

    fun sanitizeSdp(sdp: String): String {
        return sdp.lineSequence().map { line ->
            when {
                line.startsWith("a=candidate:") -> "a=" + sanitizeCandidateLine(line.removePrefix("a="))
                line.startsWith("c=IN IP4 ") || line.startsWith("c=IN IP6 ") -> sanitizeConnectionLine(line)
                else -> line
            }
        }.joinToString("\r\n")
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

    private fun sanitizeCandidateLine(line: String): String {
        val tokens = line.split(" ").toMutableList()
        if (tokens.size > CONNECTION_PORT_INDEX && isHostCandidateLine(line)) {
            tokens[CONNECTION_ADDRESS_INDEX] = placeholderFor(tokens[CONNECTION_ADDRESS_INDEX])
            tokens[CONNECTION_PORT_INDEX] = RPORT_PLACEHOLDER
        }
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
