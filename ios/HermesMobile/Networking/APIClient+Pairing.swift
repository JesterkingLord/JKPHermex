import Foundation

// v1.6.0+: request/response types for /v1/pair/* endpoints.
// Mirrors the Android `PairCompleteRequest` / `PairCompleteResponse` types.
// Tolerant decoding: unknown fields are ignored by default JSONDecoder.

struct PairCompleteRequest: Encodable {
    let pair_id: String
    let token: String
    let device_name: String
}

struct PairCompleteResponse: Decodable, Equatable {
    let pairID: String
    let deviceID: String
    let grant: String
    let tokenType: String

    enum CodingKeys: String, CodingKey {
        case pairID = "pair_id"
        case deviceID = "device_id"
        case grant
        case tokenType = "token_type"
    }
}

extension APIClient {
    /// Completes a pairing handoff. The desktop's `python -m jkp pair` mints
    /// a pair_id+token; the phone posts them here to register as a known
    /// device and receive a long-lived Bearer grant (stored in the
    /// Keychain, not yet used by APIClient — that's v1.7.0+).
    func completePairing(
        pairID: String,
        token: String,
        deviceName: String
    ) async throws -> PairCompleteResponse {
        let body = PairCompleteRequest(
            pair_id: pairID,
            token: token,
            device_name: deviceName
        )
        return try await send(
            endpoint: .pairComplete,
            method: "POST",
            body: body
        )
    }
}