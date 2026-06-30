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
import org.junit.Before
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
private const val A_REAL_INVITE_EVENT_ID = "\$realServerEventId456"

internal class MxCallImplRelatesToTest {

    private val localEchoEventFactory = mockk<LocalEchoEventFactory>()
    private val eventSenderProcessor = mockk<EventSenderProcessor>()
    private val matrixConfiguration = mockk<MatrixConfiguration>()
    private val clock = mockk<Clock>()

    @Before
    fun setUp() {
        every { localEchoEventFactory.createLocalEcho(any()) } just runs
        every { matrixConfiguration.supportsCallTransfer } returns false
        every { clock.epochMillis() } returns 1000L
        // Default stubs — individual tests override these with capturing variants
        every { eventSenderProcessor.postEvent(any<Event>()) } returns mockk()
        every { eventSenderProcessor.postEvent(any(), any(), any()) } returns mockk()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun createCallImpl(
            isOutgoing: Boolean = false,
            inviteEventId: String? = AN_INVITE_EVENT_ID
    ): MxCallImpl {
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

    // accept() and reject() build m.relates_to eagerly into the event content

    @Test
    fun `given incoming call with inviteEventId, when accept is called, then posted event content includes m_relates_to`() {
        val eventSlot = slot<Event>()
        every { eventSenderProcessor.postEvent(capture(eventSlot)) } returns mockk()

        val call = createCallImpl(isOutgoing = false)
        call.opponentUserId = "@opponent:matrix.org"
        call.accept(sdpString = "sdp-answer")

        val relatesToMap = eventSlot.captured.content?.get("m.relates_to") as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo AN_INVITE_EVENT_ID
    }

    @Test
    fun `given incoming call with inviteEventId, when reject is called, then posted event content includes m_relates_to`() {
        val eventSlot = slot<Event>()
        every { eventSenderProcessor.postEvent(capture(eventSlot)) } returns mockk()

        val call = createCallImpl(isOutgoing = false)
        call.opponentUserId = "@opponent:matrix.org"
        call.opponentVersion = 1
        call.reject()

        val relatesToMap = eventSlot.captured.content?.get("m.relates_to") as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo AN_INVITE_EVENT_ID
    }

    @Test
    fun `given call without inviteEventId, when accept is called, then posted event content has no m_relates_to`() {
        val eventSlot = slot<Event>()
        every { eventSenderProcessor.postEvent(capture(eventSlot)) } returns mockk()

        val call = createCallImpl(isOutgoing = false, inviteEventId = null)
        call.opponentUserId = "@opponent:matrix.org"
        call.accept(sdpString = "sdp-answer")

        eventSlot.captured.content?.get("m.relates_to").shouldBeNull()
    }

    // hangUp() adds m.relates_to lazily via contentModifier at actual send time

    @Test
    fun `given incoming call with inviteEventId, when hangUp is called, then content modifier provides m_relates_to`() {
        var capturedModifier: (() -> Map<String, Any>?)? = null
        every { eventSenderProcessor.postEvent(any(), any(), any()) } answers {
            capturedModifier = thirdArg()
            mockk()
        }

        val call = createCallImpl(isOutgoing = false)
        call.opponentUserId = "@opponent:matrix.org"
        call.hangUp()

        val patch = capturedModifier?.invoke()
        patch.shouldNotBeNull()
        val relatesToMap = patch["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo AN_INVITE_EVENT_ID
    }

    // offerSdp() registers onEventSent callback; once the server confirms, hangUp's contentModifier uses the real event ID

    @Test
    fun `given outgoing call, when offerSdp is called, then invite eventId is captured`() {
        val call = createCallImpl(isOutgoing = true, inviteEventId = null)
        call.opponentUserId = "@opponent:matrix.org"

        call.offerSdp(sdpString = "sdp-offer")

        call.inviteEventId.shouldNotBeNull()
    }

    @Test
    fun `given outgoing call, when server confirms invite then caller hangsUp, content modifier provides m_relates_to with real server event id`() {
        var capturedOnEventSent: ((String) -> Unit)? = null
        var capturedModifier: (() -> Map<String, Any>?)? = null

        every { eventSenderProcessor.postEvent(any(), any(), any()) } answers {
            val onSent: ((String) -> Unit)? = secondArg()
            val modifier: (() -> Map<String, Any>?)? = thirdArg()
            if (onSent != null) capturedOnEventSent = onSent
            if (modifier != null) capturedModifier = modifier
            mockk()
        }

        val call = createCallImpl(isOutgoing = true, inviteEventId = null)
        call.opponentUserId = "@opponent:matrix.org"

        call.offerSdp(sdpString = "sdp-offer")

        // Simulate the server confirming the invite with a real event ID
        capturedOnEventSent.shouldNotBeNull()
        capturedOnEventSent!!.invoke(A_REAL_INVITE_EVENT_ID)

        call.hangUp()

        // contentModifier runs at actual send time — by then capturedInviteEventId is the real server ID
        val patch = capturedModifier?.invoke()
        patch.shouldNotBeNull()
        val relatesToMap = patch["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo A_REAL_INVITE_EVENT_ID
    }
}