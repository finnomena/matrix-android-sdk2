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

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.matrix.android.sdk.api.auth.SessionTokenRefresher
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.network.httpclient.addAuthenticationHeader
import org.matrix.android.sdk.internal.network.token.TokenRefreshLoginTask
import timber.log.Timber

/**
 * OkHttp [Authenticator] invoked by OkHttp whenever an authenticated request comes back with HTTP 401.
 * It asks the host for a fresh login token (via [SessionTokenRefresher]), performs a token login reusing
 * the current device id, stores the new credentials, and returns the request so OkHttp retries it with the
 * refreshed access token. On failure it gives up and lets the usual 401 error handling surface
 * [org.matrix.android.sdk.api.failure.GlobalError.InvalidToken].
 */
internal class TokenRefreshAuthenticator(
        private val sessionId: String,
        private val sessionParamsStore: SessionParamsStore,
        private val tokenRefreshLoginTask: TokenRefreshLoginTask,
        private val sessionTokenRefresher: SessionTokenRefresher?,
) : Authenticator {

    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val refresher = sessionTokenRefresher ?: return null

        // Give up if this request already went through a refresh + retry cycle, to avoid an endless loop.
        if (responseCount(response) >= MAX_ATTEMPTS) return null

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

            response.request.newBuilder()
                    .addAuthenticationHeader(newAccessToken)
                    .build()
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
    }
}
