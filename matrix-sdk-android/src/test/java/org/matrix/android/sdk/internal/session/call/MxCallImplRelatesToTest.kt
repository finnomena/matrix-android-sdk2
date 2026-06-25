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
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.After
import org.junit.Test
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.session.call.model.MxCallImpl
import org.matrix.android.sdk.internal.session.profile.GetProfileInfoTask
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.util.time.Clock

private const val A_CALL_ID = "call-id-123"
private const val A_ROOM_ID = "!room:matrix.org"
private const val A_USER_ID = "@user:matrix.org"
private const val A_PARTY_ID = "party-id-abc"
private const val AN_INVITE_EVENT_ID = "\$inviteEventId123"

internal class MxCallImplRelatesToTest {

    private val localEchoEventFactory = mockk<LocalEchoEventFactory> {
        every { createLocalEcho(any()) } just runs
    }
    private val eventSenderProcessor = mockk<EventSenderProcessor> {
        every { postEvent(any()) } returns mockk()
    }
    private val matrixConfiguration = mockk<MatrixConfiguration> {
        every { supportsCallTransfer } returns false
    }
    private val clock = mockk<Clock> {
        every { epochMillis() } returns 1000L
    }

    private val postedEventSlot = slot<Event>()

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createCallImpl(
            isOutgoing: Boolean = false,
            inviteEventId: String? = AN_INVITE_EVENT_ID
    ): MxCallImpl {
        every { eventSenderProcessor.postEvent(capture(postedEventSlot)) } returns mockk()
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
                inviteEventId = inviteEventId,
        )
    }

    @Test
    fun `given incoming call with inviteEventId, when accept is called, then posted event content includes m_relates_to`() {
        val call = createCallImpl(isOutgoing = false)
        call.opponentUserId = "@opponent:matrix.org"

        call.accept(sdpString = "sdp-answer")

        val content = postedEventSlot.captured.content
        content.shouldNotBeNull()
        val relatesToMap = content["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo AN_INVITE_EVENT_ID
    }

    @Test
    fun `given incoming call with inviteEventId, when reject is called, then posted event content includes m_relates_to`() {
        val call = createCallImpl(isOutgoing = false)
        call.opponentUserId = "@opponent:matrix.org"
        call.opponentVersion = 1

        call.reject()

        val content = postedEventSlot.captured.content
        content.shouldNotBeNull()
        val relatesToMap = content["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo AN_INVITE_EVENT_ID
    }

    @Test
    fun `given call with inviteEventId, when hangUp is called, then posted event content includes m_relates_to`() {
        val call = createCallImpl(isOutgoing = false)
        call.opponentUserId = "@opponent:matrix.org"

        call.hangUp()

        val content = postedEventSlot.captured.content
        content.shouldNotBeNull()
        val relatesToMap = content["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo AN_INVITE_EVENT_ID
    }

    @Test
    fun `given call without inviteEventId, when accept is called, then posted event content has no m_relates_to`() {
        val call = createCallImpl(isOutgoing = false, inviteEventId = null)
        call.opponentUserId = "@opponent:matrix.org"

        call.accept(sdpString = "sdp-answer")

        val content = postedEventSlot.captured.content
        content.shouldNotBeNull()
        content["m.relates_to"].shouldBeNull()
    }

    @Test
    fun `given outgoing call, when offerSdp is called, inviteEventId is captured from local echo`() {
        val call = createCallImpl(isOutgoing = true, inviteEventId = null)
        call.opponentUserId = "@opponent:matrix.org"

        call.offerSdp(sdpString = "sdp-offer")

        call.inviteEventId.shouldNotBeNull()
    }

    @Test
    fun `given outgoing call after offerSdp, when hangUp is called, then posted event includes m_relates_to from captured invite`() {
        // Reset slot so we can capture the hangup event (offerSdp posts first)
        val events = mutableListOf<Event>()
        every { eventSenderProcessor.postEvent(any()) } answers {
            events.add(firstArg())
            mockk()
        }

        val call = MxCallImpl(
                callId = A_CALL_ID,
                isOutgoing = true,
                roomId = A_ROOM_ID,
                userId = A_USER_ID,
                isVideoCall = false,
                ourPartyId = A_PARTY_ID,
                localEchoEventFactory = localEchoEventFactory,
                eventSenderProcessor = eventSenderProcessor,
                matrixConfiguration = matrixConfiguration,
                getProfileInfoTask = mockk(),
                clock = clock,
                inviteEventId = null,
        )
        call.opponentUserId = "@opponent:matrix.org"

        call.offerSdp(sdpString = "sdp-offer")
        val capturedInviteId = call.inviteEventId
        capturedInviteId.shouldNotBeNull()

        call.hangUp()

        val hangupEvent = events[1]
        val content = hangupEvent.content
        content.shouldNotBeNull()
        val relatesToMap = content["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo capturedInviteId
    }
}
