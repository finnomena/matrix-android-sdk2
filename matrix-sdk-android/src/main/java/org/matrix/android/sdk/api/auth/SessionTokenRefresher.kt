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

package org.matrix.android.sdk.api.auth

/**
 * Provides fresh login parameters so the SDK can silently re-login and refresh the homeserver access
 * token when a request fails with HTTP 401.
 *
 * Implemented by the host application and registered via [org.matrix.android.sdk.api.MatrixConfiguration].
 * The returned map is sent as-is to the `/login` endpoint (the SDK adds the current `device_id` so the
 * refreshed session keeps the same device), then the SDK retries the failed request transparently.
 *
 * The map must at least contain the login `"type"` and its token, matching whatever flow the host used
 * to log in originally, e.g.:
 * - `mapOf("type" to "m.login.token", "token" to loginToken)`
 * - `mapOf("type" to "org.matrix.login.jwt", "token" to jwtToken)`
 *
 * Return `null` if fresh login parameters cannot be obtained (e.g. the host session has also expired);
 * the SDK will then surface the usual [org.matrix.android.sdk.api.failure.GlobalError.InvalidToken].
 */
interface SessionTokenRefresher {
    suspend fun getLoginParams(): Map<String, Any>?
}
