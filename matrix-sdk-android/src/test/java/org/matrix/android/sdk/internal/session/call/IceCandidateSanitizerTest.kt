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
    fun `given host candidate, when sanitizeCandidate, then address is zeroed`() {
        val candidate = CallCandidate(
                candidate = "candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0 ufrag abcd network-id 1"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:1 1 udp 2122260223 0.0.0.0 54321 typ host generation 0 ufrag abcd network-id 1"
    }

    @Test
    fun `given srflx candidate with raddr, when sanitizeCandidate, then address and raddr are zeroed`() {
        val candidate = CallCandidate(
                candidate = "candidate:842163049 1 udp 1677729535 203.0.113.7 54321 typ srflx raddr 192.168.1.5 rport 54321 generation 0"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:842163049 1 udp 1677729535 0.0.0.0 54321 typ srflx raddr 0.0.0.0 rport 54321 generation 0"
    }

    @Test
    fun `given relay candidate, when sanitizeCandidate, then primary address is kept but raddr is zeroed`() {
        val candidate = CallCandidate(
                candidate = "candidate:112233 1 udp 41886234 34.90.12.7 3478 typ relay raddr 192.168.1.5 rport 54321 generation 0"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:112233 1 udp 41886234 34.90.12.7 3478 typ relay raddr 0.0.0.0 rport 54321 generation 0"
    }

    @Test
    fun `given ipv6 host candidate, when sanitizeCandidate, then address is replaced with double colon`() {
        val candidate = CallCandidate(
                candidate = "candidate:1 1 udp 2122260223 2001:db8::1 54321 typ host generation 0"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:1 1 udp 2122260223 :: 54321 typ host generation 0"
    }

    @Test
    fun `given candidate with no raddr, when sanitizeCandidate, then only primary address is touched`() {
        val candidate = CallCandidate(
                candidate = "candidate:1 1 udp 2122260223 10.0.0.4 54321 typ host generation 0"
        )

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo "candidate:1 1 udp 2122260223 0.0.0.0 54321 typ host generation 0"
    }

    @Test
    fun `given malformed candidate string, when sanitizeCandidate, then it is redacted to empty`() {
        val candidate = CallCandidate(candidate = "not a real candidate line")

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate shouldBeEqualTo ""
    }

    @Test
    fun `given null candidate string, when sanitizeCandidate, then it stays null`() {
        val candidate = CallCandidate(candidate = null)

        val result = IceCandidateSanitizer.sanitizeCandidate(candidate)

        result.candidate.shouldBeNull()
    }

    @Test
    fun `given sdp with connection line and candidate line, when sanitizeSdp, then both are redacted and other lines untouched`() {
        val sdp = "v=0\r\n" +
                "o=- 12345 2 IN IP4 192.168.1.5\r\n" +
                "s=-\r\n" +
                "c=IN IP4 192.168.1.5\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
                "a=mid:0\r\n" +
                "a=candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0\r\n" +
                "a=end-of-candidates"

        val result = IceCandidateSanitizer.sanitizeSdp(sdp)

        result shouldBeEqualTo "v=0\r\n" +
                "o=- 12345 2 IN IP4 192.168.1.5\r\n" +
                "s=-\r\n" +
                "c=IN IP4 0.0.0.0\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
                "a=mid:0\r\n" +
                "a=candidate:1 1 udp 2122260223 0.0.0.0 54321 typ host generation 0\r\n" +
                "a=end-of-candidates"
    }

    @Test
    fun `given sdp with ipv6 connection line, when sanitizeSdp, then it is replaced with double colon`() {
        val sdp = "v=0\r\nc=IN IP6 2001:db8::1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"

        val result = IceCandidateSanitizer.sanitizeSdp(sdp)

        result shouldBeEqualTo "v=0\r\nc=IN IP6 ::\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111"
    }

    @Test
    fun `given sdp with relay candidate line, when sanitizeSdp, then primary address kept and raddr zeroed`() {
        val sdp = "a=candidate:112233 1 udp 41886234 34.90.12.7 3478 typ relay raddr 192.168.1.5 rport 54321 generation 0"

        val result = IceCandidateSanitizer.sanitizeSdp(sdp)

        result shouldBeEqualTo "a=candidate:112233 1 udp 41886234 34.90.12.7 3478 typ relay raddr 0.0.0.0 rport 54321 generation 0"
    }

    @Test
    fun `given sdp with malformed candidate line embedded, when sanitizeSdp, then malformed line is redacted to empty`() {
        val sdp = "v=0\r\n" +
                "a=candidate:garbage\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111"

        val result = IceCandidateSanitizer.sanitizeSdp(sdp)

        result shouldBeEqualTo "v=0\r\n" +
                "\r\n" +
                "m=audio 9 UDP/TLS/RTP/SAVPF 111"
    }
}
