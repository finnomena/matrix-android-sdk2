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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.auth.SessionTokenRefresher
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.network.token.TokenRefreshLoginTask
import org.matrix.android.sdk.internal.util.time.Clock

class TokenRefreshAuthenticatorTest {

    private val aSessionId = "a-session-id"
    private val sessionParamsStore = mockk<SessionParamsStore>(relaxed = true)
    private val tokenRefreshLoginTask = mockk<TokenRefreshLoginTask>()
    private val sessionTokenRefresher = mockk<SessionTokenRefresher>()
    private var nowMs = 0L
    private val clock = mockk<Clock> { every { epochMillis() } answers { nowMs } }

    private val authenticator = TokenRefreshAuthenticator(
            sessionId = aSessionId,
            sessionParamsStore = sessionParamsStore,
            tokenRefreshLoginTask = tokenRefreshLoginTask,
            sessionTokenRefresher = sessionTokenRefresher,
            clock = clock,
            moshi = MoshiProvider.providesMoshi(),
    )

    @Test
    fun `given a refresher provides login params when authenticate then re-logins with those params and retries with the refreshed access token`() {
        val loginParams = mapOf<String, Any>("type" to "org.matrix.login.jwt", "token" to "a-jwt")
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")
        coEvery { sessionTokenRefresher.getLoginParams() } returns loginParams
        coEvery { tokenRefreshLoginTask.execute(loginParams) } returns credentials("new-token")

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result?.header(HttpHeaders.Authorization) shouldBeEqualTo "Bearer new-token"
        coVerify { tokenRefreshLoginTask.execute(loginParams) }
        coVerify { sessionParamsStore.updateCredentials(match { it.accessToken == "new-token" }) }
    }

    @Test
    fun `given a 401 that is not an unknown token error when authenticate then does not refresh`() {
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token", errcode = "M_FORBIDDEN"))

        result.shouldBeNull()
        coVerify(exactly = 0) { sessionTokenRefresher.getLoginParams() }
    }

    @Test
    fun `given the stored token already changed when authenticate then retries with the stored token without refreshing`() {
        // Another concurrent 401 already refreshed the token in the store.
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("already-refreshed-token")

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result?.header(HttpHeaders.Authorization) shouldBeEqualTo "Bearer already-refreshed-token"
        coVerify(exactly = 0) { sessionTokenRefresher.getLoginParams() }
    }

    @Test
    fun `given the request was already retried when authenticate then gives up`() {
        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token", alreadyRetried = true))

        result.shouldBeNull()
        coVerify(exactly = 0) { sessionTokenRefresher.getLoginParams() }
    }

    @Test
    fun `given the refresher cannot provide login params when authenticate then gives up`() {
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")
        coEvery { sessionTokenRefresher.getLoginParams() } returns null

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result.shouldBeNull()
        coVerify(exactly = 0) { tokenRefreshLoginTask.execute(any()) }
    }

    @Test
    fun `given the re-login fails when authenticate then gives up`() {
        val loginParams = mapOf<String, Any>("type" to "m.login.token", "token" to "a-login-token")
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")
        coEvery { sessionTokenRefresher.getLoginParams() } returns loginParams
        coEvery { tokenRefreshLoginTask.execute(loginParams) } throws IllegalStateException("login failed")

        val result = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        result.shouldBeNull()
    }

    @Test
    fun `given a freshly minted token is rejected again within the cool-down when authenticate then gives up without refreshing again`() {
        val loginParams = mapOf<String, Any>("type" to "m.login.token", "token" to "a-login-token")
        coEvery { sessionTokenRefresher.getLoginParams() } returns loginParams
        coEvery { tokenRefreshLoginTask.execute(loginParams) } returns credentials("new-token")

        // First 401: refresh old-token -> new-token.
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")
        val first = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))
        first?.header(HttpHeaders.Authorization) shouldBeEqualTo "Bearer new-token"

        // Second 401 rejects the token we just minted, still within the cool-down window.
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("new-token")
        nowMs += 1_000
        val second = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "new-token"))

        second.shouldBeNull()
        coVerify(exactly = 1) { sessionTokenRefresher.getLoginParams() }
    }

    @Test
    fun `given a freshly minted token is rejected after the cool-down when authenticate then refreshes again`() {
        val loginParams = mapOf<String, Any>("type" to "m.login.token", "token" to "a-login-token")
        coEvery { sessionTokenRefresher.getLoginParams() } returns loginParams
        coEvery { tokenRefreshLoginTask.execute(loginParams) } returns credentials("new-token")

        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("old-token")
        authenticator.authenticate(route = null, response = unauthorizedResponse(token = "old-token"))

        // The minted token is rejected only after the cool-down elapsed -> a new refresh is allowed.
        every { sessionParamsStore.get(aSessionId) } returns sessionParamsWithToken("new-token")
        coEvery { tokenRefreshLoginTask.execute(loginParams) } returns credentials("newer-token")
        nowMs += 11_000
        val second = authenticator.authenticate(route = null, response = unauthorizedResponse(token = "new-token"))

        second?.header(HttpHeaders.Authorization) shouldBeEqualTo "Bearer newer-token"
        coVerify(exactly = 2) { sessionTokenRefresher.getLoginParams() }
    }

    @Test
    fun `given no refresher is configured when authenticate then gives up`() {
        val authenticatorWithoutRefresher = TokenRefreshAuthenticator(
                sessionId = aSessionId,
                sessionParamsStore = sessionParamsStore,
                tokenRefreshLoginTask = tokenRefreshLoginTask,
                sessionTokenRefresher = null,
                clock = clock,
                moshi = MoshiProvider.providesMoshi(),
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

    private fun unauthorizedResponse(token: String?, alreadyRetried: Boolean = false, errcode: String = "M_UNKNOWN_TOKEN"): Response {
        val requestBuilder = Request.Builder().url("https://example.org/_matrix/client/v3/sync")
        if (token != null) {
            requestBuilder.header(HttpHeaders.Authorization, "Bearer $token")
        }
        val request = requestBuilder.build()
        val errorBody = """{"errcode":"$errcode","error":"token error"}""".toResponseBody("application/json".toMediaType())
        return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body(errorBody)
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
