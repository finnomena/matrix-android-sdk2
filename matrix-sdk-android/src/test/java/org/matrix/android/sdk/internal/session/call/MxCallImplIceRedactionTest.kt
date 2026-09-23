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

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.model.call.CallCandidate
import org.matrix.android.sdk.api.session.room.model.call.SdpType
import org.matrix.android.sdk.internal.session.call.model.MxCallImpl
import org.matrix.android.sdk.internal.session.profile.GetProfileInfoTask
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.util.time.Clock

private const val A_CALL_ID = "call-id-123"
private const val A_ROOM_ID = "!room:matrix.org"
private const val A_USER_ID = "@user:matrix.org"
private const val A_PARTY_ID = "party-id-abc"
private const val A_REAL_SDP_WITH_IP = "v=0\r\nc=IN IP4 192.168.1.5\r\na=candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0"
private const val A_SANITIZED_SDP = "v=0\r\nc=IN IP4 0.0.0.0"

internal class MxCallImplIceRedactionTest {

    private val localEchoEventFactory = mockk<LocalEchoEventFactory>()
    private val eventSenderProcessor = mockk<EventSenderProcessor>()
    private val matrixConfiguration = mockk<MatrixConfiguration>()
    private val clock = mockk<Clock>()

    @Before
    fun setUp() {
        every { localEchoEventFactory.createLocalEcho(any()) } just runs
        every { matrixConfiguration.supportsCallTransfer } returns false
        every { clock.epochMillis() } returns 1000L
        every { eventSenderProcessor.postEvent(any<Event>()) } returns mockk()
        every { eventSenderProcessor.postEvent(any(), any(), any()) } returns mockk()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createCallImpl(isOutgoing: Boolean): MxCallImpl {
        return MxCallImpl(
                callId = A_CALL_ID,
                isOutgoing = isOutgoing,
                roomId = A_ROOM_ID,
                userId = A_USER_ID,
                isVideoCall = false,
                ourPartyId = A_PARTY_ID,
                localEchoEventFactory = localEchoEventFactory,
                eventSenderProcessor = eventSenderProcessor,
                matrixConfiguration = matrixConfiguration,
                getProfileInfoTask = mockk<GetProfileInfoTask>(),
                clock = clock,
                inviteEventId = null,
        )
    }

    @Test
    fun `given outgoing call, when offerSdp is called with a real IP in the sdp, then posted event content has the sanitized sdp`() {
        val eventSlot = slot<Event>()
        every { eventSenderProcessor.postEvent(capture(eventSlot), any(), any()) } returns mockk()

        val call = createCallImpl(isOutgoing = true)
        call.offerSdp(sdpString = A_REAL_SDP_WITH_IP)

        val offer = eventSlot.captured.content?.get("offer") as? Map<*, *>
        offer?.get("sdp") shouldBeEqualTo A_SANITIZED_SDP
    }

    @Test
    fun `given incoming call, when accept is called with a real IP in the sdp, then posted event content has the sanitized sdp`() {
        val eventSlot = slot<Event>()
        every { eventSenderProcessor.postEvent(capture(eventSlot)) } returns mockk()

        val call = createCallImpl(isOutgoing = false)
        call.opponentUserId = "@opponent:matrix.org"
        call.accept(sdpString = A_REAL_SDP_WITH_IP)

        val answer = eventSlot.captured.content?.get("answer") as? Map<*, *>
        answer?.get("sdp") shouldBeEqualTo A_SANITIZED_SDP
    }

    @Test
    fun `given call, when negotiate is called with a real IP in the sdp, then posted event content has the sanitized sdp`() {
        val eventSlot = slot<Event>()
        every { eventSenderProcessor.postEvent(capture(eventSlot)) } returns mockk()

        val call = createCallImpl(isOutgoing = true)
        call.negotiate(sdpString = A_REAL_SDP_WITH_IP, type = SdpType.OFFER)

        val description = eventSlot.captured.content?.get("description") as? Map<*, *>
        description?.get("sdp") shouldBeEqualTo A_SANITIZED_SDP
    }

    @Test
    fun `given call, when sendLocalCallCandidates is called with only a host candidate, then no event is posted`() {
        val call = createCallImpl(isOutgoing = true)
        call.sendLocalCallCandidates(
                listOf(
                        CallCandidate(
                                sdpMid = "0",
                                sdpMLineIndex = 0,
                                candidate = "candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0"
                        )
                )
        )

        verify(exactly = 0) { eventSenderProcessor.postEvent(any<Event>()) }
    }

    @Test
    fun `given call, when sendLocalCallCandidates is called with a relay candidate, then posted event keeps the primary address intact`() {
        val eventSlot = slot<Event>()
        every { eventSenderProcessor.postEvent(capture(eventSlot)) } returns mockk()

        val call = createCallImpl(isOutgoing = true)
        call.sendLocalCallCandidates(
                listOf(
                        CallCandidate(
                                sdpMid = "0",
                                sdpMLineIndex = 0,
                                candidate = "candidate:1 1 udp 41886234 34.90.12.7 3478 typ relay raddr 192.168.1.5 rport 54321 generation 0"
                        )
                )
        )

        val candidates = eventSlot.captured.content?.get("candidates") as? List<*>
        val firstCandidate = candidates?.firstOrNull() as? Map<*, *>
        firstCandidate?.get("candidate") shouldBeEqualTo "candidate:1 1 udp 41886234 34.90.12.7 3478 typ relay raddr 0.0.0.0 rport 54321 generation 0"
    }

    @Test
    fun `given call, when sendLocalCallCandidates is called with a mix of host and relay candidates, then only the relay candidate is posted`() {
        val eventSlot = slot<Event>()
        every { eventSenderProcessor.postEvent(capture(eventSlot)) } returns mockk()

        val call = createCallImpl(isOutgoing = true)
        call.sendLocalCallCandidates(
                listOf(
                        CallCandidate(
                                sdpMid = "0",
                                sdpMLineIndex = 0,
                                candidate = "candidate:1 1 udp 2122260223 192.168.1.5 54321 typ host generation 0"
                        ),
                        CallCandidate(
                                sdpMid = "0",
                                sdpMLineIndex = 0,
                                candidate = "candidate:2 1 udp 41886234 34.90.12.7 3478 typ relay generation 0"
                        )
                )
        )

        val candidates = eventSlot.captured.content?.get("candidates") as? List<*>
        candidates?.size shouldBeEqualTo 1
        val onlyCandidate = candidates?.firstOrNull() as? Map<*, *>
        onlyCandidate?.get("candidate") shouldBeEqualTo "candidate:2 1 udp 41886234 34.90.12.7 3478 typ relay generation 0"
    }
}
