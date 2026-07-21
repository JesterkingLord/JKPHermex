package com.hermexapp.android.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * JKP mobile pairing freeze (v1.13 / Hermex 0.6): attach
 * `Authorization: Bearer <grant>` when a per-host pairing grant is available.
 *
 * - Does **not** log or put the grant in query strings.
 * - Never overwrites an existing Authorization header (admin key / tests).
 * - Password/cookie servers keep working: when no grant is stored, this is a no-op
 *   and [SessionCookieJar] continues to supply session cookies.
 *
 * Grant lookup is host-scoped (same as [com.hermexapp.android.auth.SecretStore.Key.PAIR_GRANT]).
 */
class BearerAuthInterceptor(
    private val grantForHost: (host: String) -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        val grant = grantForHost(request.url.host)?.trim().orEmpty()
        if (grant.isEmpty()) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $grant")
                .build(),
        )
    }
}
