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

package org.matrix.android.sdk.internal.network

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.auth.SessionTokenRefresher
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.network.token.TokenRefreshLoginTask

class TokenRefreshAuthenticatorTest {

    private val aSessionId = "a-session-id"
    private val sessionParamsStore = mockk<SessionParamsStore>(relaxed = true)
    private val tokenRefreshLoginTask = mockk<TokenRefreshLoginTask>()
    private val sessionTokenRefresher = mockk<SessionTokenRefresher>()

    private val authenticator = TokenRefreshAuthenticator(
            sessionId = aSessionId,
            sessionParamsStore = sessionParamsStore,
            tokenRefreshLoginTask = tokenRefreshLoginTask,
            sessionTokenRefresher = sessionTokenRefresher,
    )

    @Test
    fun `given a refresher provides a token when authenticate then retries request with the refreshed access token`() {
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")
        coEvery { sessionTokenRefresher.getNewLoginToken() } returns "a-login-token"
        coEvery { tokenRefreshLoginTask.execute("a-login-token") } returns credentials("new-token")

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result?.header(HttpHeaders.Authorization) shouldBeEqualTo "Bearer new-token"
        coVerify { sessionParamsStore.updateCredentials(match { it.accessToken == "new-token" }) }
    }

    @Test
    fun `given the stored token already changed when authenticate then retries with the stored token without refreshing`() {
        // Another concurrent 401 already refreshed the token in the store.
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("already-refreshed-token")

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result?.header(HttpHeaders.Authorization) shouldBeEqualTo "Bearer already-refreshed-token"
        coVerify(exactly = 0) { sessionTokenRefresher.getNewLoginToken() }
    }

    @Test
    fun `given the request was already retried when authenticate then gives up`() {
        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token", alreadyRetried = true))

        result.shouldBeNull()
        coVerify(exactly = 0) { sessionTokenRefresher.getNewLoginToken() }
    }

    @Test
    fun `given the refresher cannot provide a login token when authenticate then gives up`() {
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")
        coEvery { sessionTokenRefresher.getNewLoginToken() } returns null

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result.shouldBeNull()
        coVerify(exactly = 0) { tokenRefreshLoginTask.execute(any()) }
    }

    @Test
    fun `given the token login fails when authenticate then gives up`() {
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")
        coEvery { sessionTokenRefresher.getNewLoginToken() } returns "a-login-token"
        coEvery { tokenRefreshLoginTask.execute("a-login-token") } throws IllegalStateException("login failed")

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result.shouldBeNull()
    }

    @Test
    fun `given no refresher is configured when authenticate then gives up`() {
        val authenticatorWithoutRefresher = TokenRefreshAuthenticator(
                sessionId = aSessionId,
                sessionParamsStore = sessionParamsStore,
                tokenRefreshLoginTask = tokenRefreshLoginTask,
                sessionTokenRefresher = null,
        )

        val result = authenticatorWithoutRefresher.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result.shouldBeNull()
    }

    private fun credentials(accessToken: String) = Credentials(
            userId = "@user:example.org",
            accessToken = accessToken,
            refreshToken = null,
            homeServer = null,
            deviceId = "A_DEVICE",
    )

    private fun sessionParamsWithToken(accessToken: String): SessionParams = mockk {
        every { credentials } returns credentials(accessToken)
    }

    private fun unauthorizedResponse(token: String?, alreadyRetried: Boolean = false): Response {
        val requestBuilder = Request.Builder().url("https://example.org/_matrix/client/v3/sync")
        if (token != null) {
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $token")
        }
        val request = requestBuilder.build()
        return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .apply {
                    if (alreadyRetried) {
                        // Simulate a 401 that already went through one refresh+retry cycle.
                        priorResponse(
                                Response.Builder()
                                        .request(request)
                                        .protocol(Protocol.HTTP_1_1)
                                        .code(401)
                                        .message("Unauthorized")
                                        .build()
                        )
                    }
                }
                .build()
    }
}
