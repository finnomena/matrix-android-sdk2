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
 * Provides a fresh login token (`m.login.token`) so the SDK can silently re-login and
 * refresh the homeserver access token when a request fails with HTTP 401.
 *
 * Implemented by the host application and registered via [org.matrix.android.sdk.api.MatrixConfiguration].
 * The SDK uses the returned token to perform a token login (reusing the current device id) and then
 * retries the failed request transparently.
 *
 * Return `null` if a fresh login token cannot be obtained (e.g. the host session has also expired);
 * the SDK will then surface the usual [org.matrix.android.sdk.api.failure.GlobalError.InvalidToken].
 */
interface SessionTokenRefresher {
    suspend fun getNewLoginToken(): String?
}
