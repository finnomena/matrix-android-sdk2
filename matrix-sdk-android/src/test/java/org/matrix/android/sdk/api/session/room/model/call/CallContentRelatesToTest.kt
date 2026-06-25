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

package org.matrix.android.sdk.api.session.room.model.call

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent

class CallContentRelatesToTest {

    private val aRelatesTo = RelationDefaultContent(
            type = MxCall.VOIP_RELATION_TYPE,
            eventId = "\$inviteEventId"
    )

    // --- CallAnswerContent ---

    @Test
    fun `given relatesTo is set, CallAnswerContent serializes m_relates_to with rel_type and event_id`() {
        val content = CallAnswerContent(
                callId = "call-id",
                answer = CallAnswerContent.Answer(sdp = "sdp"),
                version = "1",
                relatesTo = aRelatesTo
        ).toContent()

        val relatesToMap = content["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo "\$inviteEventId"
    }

    @Test
    fun `given relatesTo is null, CallAnswerContent omits m_relates_to from serialized content`() {
        val content = CallAnswerContent(
                callId = "call-id",
                answer = CallAnswerContent.Answer(sdp = "sdp"),
                version = "1",
                relatesTo = null
        ).toContent()

        content["m.relates_to"].shouldBeNull()
    }

    // --- CallHangupContent ---

    @Test
    fun `given relatesTo is set, CallHangupContent serializes m_relates_to with rel_type and event_id`() {
        val content = CallHangupContent(
                callId = "call-id",
                version = "1",
                relatesTo = aRelatesTo
        ).toContent()

        val relatesToMap = content["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo "\$inviteEventId"
    }

    @Test
    fun `given relatesTo is null, CallHangupContent omits m_relates_to from serialized content`() {
        val content = CallHangupContent(
                callId = "call-id",
                version = "1",
                relatesTo = null
        ).toContent()

        content["m.relates_to"].shouldBeNull()
    }

    // --- CallRejectContent ---

    @Test
    fun `given relatesTo is set, CallRejectContent serializes m_relates_to with rel_type and event_id`() {
        val content = CallRejectContent(
                callId = "call-id",
                version = "1",
                relatesTo = aRelatesTo
        ).toContent()

        val relatesToMap = content["m.relates_to"] as? Map<*, *>
        relatesToMap.shouldNotBeNull()
        relatesToMap["rel_type"] shouldBeEqualTo MxCall.VOIP_RELATION_TYPE
        relatesToMap["event_id"] shouldBeEqualTo "\$inviteEventId"
    }

    @Test
    fun `given relatesTo is null, CallRejectContent omits m_relates_to from serialized content`() {
        val content = CallRejectContent(
                callId = "call-id",
                version = "1",
                relatesTo = null
        ).toContent()

        content["m.relates_to"].shouldBeNull()
    }
}
