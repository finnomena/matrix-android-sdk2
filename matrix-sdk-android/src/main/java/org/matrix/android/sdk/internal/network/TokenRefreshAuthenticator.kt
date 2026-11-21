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

import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.matrix.android.sdk.api.auth.SessionTokenRefresher
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.network.httpclient.addAuthenticationHeader
import org.matrix.android.sdk.internal.network.token.TokenRefreshLoginTask
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber

/**
 * OkHttp [Authenticator] invoked by OkHttp whenever an authenticated request comes back with HTTP 401.
 *
 * When the homeserver reports the token is unknown ([MatrixError.M_UNKNOWN_TOKEN]) it asks the host for
 * fresh login params (via [SessionTokenRefresher]), re-logins reusing the current device id, stores the
 * new credentials and returns the request so OkHttp retries it with the refreshed access token.
 *
 * Guards:
 * - Only reacts to `M_UNKNOWN_TOKEN` 401s (other 401s are left untouched).
 * - Single-flight: one refresh at a time; concurrent 401s reuse the freshly stored token.
 * - Loop guard: at most one refresh + retry per request (via `priorResponse`).
 * - Cool-down circuit breaker: if a token that was *just* minted here is rejected again within
 *   [REFRESH_COOL_DOWN_MS], it stops refreshing and lets the usual
 *   [org.matrix.android.sdk.api.failure.GlobalError.InvalidToken] surface, so repeated incoming requests
 *   cannot drive an endless refresh/login storm when the homeserver keeps rejecting fresh tokens.
 */
internal class TokenRefreshAuthenticator(
        private val sessionId: String,
        private val sessionParamsStore: SessionParamsStore,
        private val tokenRefreshLoginTask: TokenRefreshLoginTask,
        private val sessionTokenRefresher: SessionTokenRefresher?,
        private val clock: Clock,
        moshi: Moshi,
) : Authenticator {

    private val matrixErrorAdapter = moshi.adapter(MatrixError::class.java)
    private val refreshLock = Any()

    // Guarded by refreshLock.
    private var lastRefreshedToken: String? = null
    private var lastRefreshAtMs: Long = 0L

    override fun authenticate(route: Route?, response: Response): Request? {
        val refresher = sessionTokenRefresher ?: return null

        // Give up if this request already went through a refresh + retry cycle, to avoid an endless loop.
        if (responseCount(response) >= MAX_ATTEMPTS) return null

        // Only react to 401s that a re-login can actually recover (unknown/missing token). Other token
        // errors such as ORG_MATRIX_EXPIRED_ACCOUNT are left to surface, since minting a new token would
        // not fix them.
        if (!response.isRecoverableTokenError()) return null

        val rejectedToken = response.request.header(HttpHeaders.Authorization)?.removePrefix(BEARER_PREFIX)

        // Single-flight: only one refresh happens at a time. Concurrent 401s that reach the lock after the
        // token was already refreshed simply retry with the stored token instead of refreshing again.
        return synchronized(refreshLock) {
            val storedToken = sessionParamsStore.get(sessionId)?.credentials?.accessToken
            if (storedToken != null && storedToken != rejectedToken) {
                return@synchronized response.request.newBuilder()
                        .addAuthenticationHeader(storedToken)
                        .build()
            }

            // Circuit breaker: the token being rejected is the one we just minted, within the cool-down
            // window. Refreshing again would only mint another token the server will reject -> give up.
            if (rejectedToken != null &&
                    rejectedToken == lastRefreshedToken &&
                    clock.epochMillis() - lastRefreshAtMs < REFRESH_COOL_DOWN_MS) {
                return@synchronized null
            }

            val newAccessToken = runBlocking {
                val loginParams = refresher.getLoginParams() ?: return@runBlocking null
                try {
                    val credentials = tokenRefreshLoginTask.execute(loginParams)
                    sessionParamsStore.updateCredentials(credentials)
                    credentials.accessToken
                } catch (throwable: Throwable) {
                    Timber.e(throwable, "Failed to refresh the access token after a 401")
                    null
                }
            } ?: return@synchronized null

            lastRefreshedToken = newAccessToken
            lastRefreshAtMs = clock.epochMillis()

            response.request.newBuilder()
                    .addAuthenticationHeader(newAccessToken)
                    .build()
        }
    }

    private fun Response.isRecoverableTokenError(): Boolean {
        return try {
            val body = peekBody(MAX_ERROR_BODY_BYTES).string()
            matrixErrorAdapter.fromJson(body)?.code in RECOVERABLE_TOKEN_ERRORS
        } catch (throwable: Throwable) {
            false
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var priorResponse = response.priorResponse
        while (priorResponse != null) {
            count++
            priorResponse = priorResponse.priorResponse
        }
        return count
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val BEARER_PREFIX = "Bearer "
        private const val REFRESH_COOL_DOWN_MS = 10_000L
        private const val MAX_ERROR_BODY_BYTES = 10_000L

        // Token errors a silent re-login can actually recover. ORG_MATRIX_EXPIRED_ACCOUNT is intentionally
        // excluded: a fresh token does not un-expire the account, so it must surface instead.
        private val RECOVERABLE_TOKEN_ERRORS = setOf(
                MatrixError.M_UNKNOWN_TOKEN,
                MatrixError.M_MISSING_TOKEN,
        )
    }
}
