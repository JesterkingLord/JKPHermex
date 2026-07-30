package com.hermexapp.android.model

import kotlinx.serialization.Serializable

/**
 * Response body of `POST /v1/pair/complete`. Mirrors the contract the
 * desktop side documents in `api_server.py:_handle_pair_complete`:
 *
 *   {"pair_id": str, "device_id": str, "grant": str, "token_type": "Bearer"}
 *
 * `grant` is the long-lived Bearer token the phone should use for all
 * subsequent requests. We don't persist it directly here — that lives in
 * SecretStore — but parsing is tolerant: unknown fields are ignored, and
 * `token_type` defaults to "Bearer" if the server omits it.
 */
@Serializable
data class PairCompleteResponse(
    val pair_id: String = "",
    val device_id: String = "",
    val grant: String = "",
    val token_type: String = "Bearer",
)

@Serializable
data class PairCompleteRequest(
    val pair_id: String,
    val token: String,
    val device_name: String,
)