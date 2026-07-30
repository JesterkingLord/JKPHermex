package com.hermexapp.android.network

import com.hermexapp.android.model.PairCompleteRequest
import com.hermexapp.android.model.PairCompleteResponse

/**
 * Endpoint family for short-lived JKP mobile pairing. Mirrors the
 * iOS `APIClient+Pairing` extension. Lives in its own file because the
 * pairing endpoints cross server boundaries — the phone doesn't yet
 * trust the server, so it must construct a one-shot client per scan
 * (see [PairingClient] below), not reuse a host's saved client.
 */

suspend fun ApiClient.completePairing(
    pairId: String,
    token: String,
    deviceName: String,
): PairCompleteResponse = postJson(
    Endpoint.PAIR_COMPLETE,
    json.encodeToString(
        PairCompleteRequest.serializer(),
        PairCompleteRequest(pairId, token, deviceName),
    ),
)