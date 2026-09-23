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

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.call.CallCandidate

internal class IceCandidateSanitizerTest {

    @Test
    fun `given relay candidate with raddr and rport, when sanitizeCandidate, then raddr and rport are blanked and primary address is untouched`() {
        val candidate = CallCandidate(
                candidate = "candidate:1155324407 1 udp 41820415 34.87.27.160 49157 typ relay raddr 49.230.59.36 rport 49174 generation 0 ufrag V+lk network-id 1 network-cost 10"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:1155324407 1 udp 41820415 34.87.27.160 49157 typ relay raddr 0.0.0.0 rport 9 generation 0 ufrag V+lk network-id 1 network-cost 10"
    }

    @Test
    fun `given srflx candidate with raddr and rport, when sanitizeCandidate, then raddr and rport are blanked and primary address is untouched`() {
        val candidate = CallCandidate(
                candidate = "candidate:842163049 1 udp 1677729535 203.0.113.7 54321 typ srflx raddr 192.168.1.5 rport 54321 generation 0"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:842163049 1 udp 1677729535 203.0.113.7 54321 typ srflx raddr 0.0.0.0 rport 9 generation 0"
    }

    @Test
    fun `given host candidate with no raddr or rport, when sanitizeCandidate, then it is left completely unchanged`() {
        val candidate = CallCandidate(
                candidate = "candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0 ufrag abcd network-id 1"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0 ufrag abcd network-id 1"
    }

    @Test
    fun `given candidate with ipv6 raddr, when sanitizeCandidate, then raddr is replaced with double colon`() {
        val candidate = CallCandidate(
                candidate = "candidate:1 1 udp 1677729535 34.87.27.160 3478 typ relay raddr 2001:db8::1 rport 54321 generation 0"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:1 1 udp 1677729535 34.87.27.160 3478 typ relay raddr :: rport 9 generation 0"
    }

    @Test
    fun `given candidate with raddr but no rport, when sanitizeCandidate, then only raddr is blanked`() {
        val candidate = CallCandidate(
                candidate = "candidate:1 1 udp 1677729535 34.87.27.160 3478 typ relay raddr 192.168.1.5 generation 0"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:1 1 udp 1677729535 34.87.27.160 3478 typ relay raddr 0.0.0.0 generation 0"
    }

    @Test
    fun `given malformed candidate string with no raddr or rport, when sanitizeCandidate, then it is left unchanged`() {
        val candidate = CallCandidate(candidate = "not a real candidate line")

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "not a real candidate line"
    }

    @Test
    fun `given null candidate string, when sanitizeCandidate, then it stays null`() {
        val candidate = CallCandidate(candidate = null)

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate.shouldBeNull()
    }

    @Test
    fun `given sdp with connection line and a relay candidate line, when sanitizeSdp, then connection line is zeroed and raddr rport are blanked`() {
        val sdp = "v=0\r\n" +
                "o=- 12345 2 IN IP4 192.168.1.5\r\n" +
                "s=-\r\n" +
                "c=IN IP4 192.168.1.5\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
                "a=mid:0\r\n" +
                "a=candidate:1 1 udp 41820415 34.87.27.160 49157 typ relay raddr 192.168.1.5 rport 54321 generation 0\r\n" +
                "a=end-of-candidates"

        val result = IceCandidateSanitizer.sanitizeSdp(sdp)

        result shouldBeEqualTo "v=0\r\n" +
                "o=- 12345 2 IN IP4 192.168.1.5\r\n" +
                "s=-\r\n" +
                "c=IN IP4 0.0.0.0\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
                "a=mid:0\r\n" +
                "a=candidate:1 1 udp 41820415 34.87.27.160 49157 typ relay raddr 0.0.0.0 rport 9 generation 0\r\n" +
                "a=end-of-candidates"
    }

    @Test
    fun `given sdp with ipv6 connection line, when sanitizeSdp, then it is replaced with double colon`() {
        val sdp = "v=0\r\nc=IN IP6 2001:db8::1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"

        val result = IceCandidateSanitizer.sanitizeSdp(sdp)

        result shouldBeEqualTo "v=0\r\nc=IN IP6 ::\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"
    }

    @Test
    fun `given sdp with a host candidate line (no raddr rport), when sanitizeSdp, then the line is left unchanged`() {
        val sdp = "v=0\r\n" +
                "a=candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111"

        val result = IceCandidateSanitizer.sanitizeSdp(sdp)

        result shouldBeEqualTo sdp
    }

    @Test
    fun `given sdp with mixed host, srflx and relay candidate lines, when sanitizeSdp, then all lines survive with only raddr rport blanked`() {
        val sdp = "v=0\r\n" +
                "a=candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0\r\n" +
                "a=candidate:2 1 udp 1685987071 203.0.113.7 54321 typ srflx raddr 192.168.1.5 rport 54321 generation 0\r\n" +
                "a=candidate:3 1 udp 41886234 34.90.12.7 3478 typ relay raddr 192.168.1.5 rport 54321 generation 0\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111"

        val result = IceCandidateSanitizer.sanitizeSdp(sdp)

        result shouldBeEqualTo "v=0\r\n" +
                "a=candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0\r\n" +
                "a=candidate:2 1 udp 1685987071 203.0.113.7 54321 typ srflx raddr 0.0.0.0 rport 9 generation 0\r\n" +
                "a=candidate:3 1 udp 41886234 34.90.12.7 3478 typ relay raddr 0.0.0.0 rport 9 generation 0\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111"
    }
}
