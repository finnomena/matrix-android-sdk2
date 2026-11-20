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

package org.matrix.android.sdk.internal.network.token

import dagger.Lazy
import okhttp3.OkHttpClient
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.internal.auth.AuthAPI
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.auth.data.TokenLoginParams
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.Unauthenticated
import org.matrix.android.sdk.internal.network.RetrofitFactory
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.network.httpclient.addSocketFactory
import javax.inject.Inject

internal class DefaultTokenRefreshLoginTask @Inject constructor(
        @SessionId private val sessionId: String,
        @Unauthenticated private val okHttpClient: Lazy<OkHttpClient>,
        private val retrofitFactory: RetrofitFactory,
        private val sessionParamsStore: SessionParamsStore,
) : TokenRefreshLoginTask {

    override suspend fun execute(loginToken: String): Credentials {
        val sessionParams = sessionParamsStore.get(sessionId)
                ?: error("No session params found for $sessionId")
        val homeServerConnectionConfig = sessionParams.homeServerConnectionConfig
        val homeServerUrl = homeServerConnectionConfig.homeServerUriBase.toString()

        val client = okHttpClient.get()
                .newBuilder()
                .addSocketFactory(homeServerConnectionConfig)
                .build()

        val authAPI = retrofitFactory.create(client, homeServerUrl)
                .create(AuthAPI::class.java)

        // Reuse the current device id so the refreshed session keeps the same device (and its crypto state).
        val loginParams = TokenLoginParams(
                token = loginToken,
                deviceId = sessionParams.deviceId,
        )

        return executeRequest(null) {
            authAPI.login(loginParams)
        }
    }
}
