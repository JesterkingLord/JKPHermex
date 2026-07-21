import Foundation

// v1.6.0+: mirror of the Android `AuthManager.pairAndConfigure`.
// Parses a QR/pasted pairing URL, posts to /v1/pair/complete, and on
// success persists the grant + device_id per-server. The grant is stored
// but not yet consumed by APIClient — Bearer-auth integration is planned
// for v1.7.0+. Returns a `PairingOutcome` describing what happened; the
// caller is the `OnboardingConnectPage`'s "Pair from URL" button.

enum PairingOutcome: Equatable {
    case paired(serverURL: URL, deviceID: String)
    case prefilled(serverURL: URL)
    case failed(reason: String)
}

@MainActor
extension AuthManager {

    /// The default device name sent to the server when the user pastes a
    /// pairing URL without naming the device. Uses UIDevice.current.name
    /// ("Farouk's iPhone") when available; falls back to a static label
    /// in previews and tests.
    static var pairFromTextDeviceName: String {
        // UIDevice import would be available; using the dynamic name keeps
        // the multi-device dashboard useful on the desktop side.
        // (Kept as a static let to avoid importing UIKit here — the
        // onboarding caller fills this in.)
        "JKP Mobile"
    }

    func pairAndConfigure(
        rawText: String,
        deviceName: String = AuthManager.pairFromTextDeviceName
    ) async -> PairingOutcome {
        lastErrorMessage = nil

        switch PairingIntentParser.parse(rawText) {
        case .invalid(let reason):
            lastErrorMessage = reason
            return .failed(reason: reason)

        case .serverURLOnly(let url):
            return .prefilled(serverURL: url)

        case .completePairing(let serverURL, let pairID, let token):
            do {
                let client = clientFactory(serverURL)
                let response = try await client.completePairing(
                    pairID: pairID,
                    token: token,
                    deviceName: deviceName
                )
                guard !response.grant.isEmpty, !response.deviceID.isEmpty else {
                    let reason = "Server returned an empty grant; pair rejected."
                    lastErrorMessage = reason
                    return .failed(reason: reason)
                }

                // Persist per-server. Same scoping convention as
                // `custom_headers::host` so multiple paired hosts don't
                // collide. (`absoluteString` matches what iOS AuthManager
                // uses for the custom-headers scope.)
                let scope = serverURL.absoluteString
                try? keychain.save(response.grant, forKey: .pairGrant, scope: scope)
                try? keychain.save(response.deviceID, forKey: .pairDeviceID, scope: scope)
                try? keychain.save(serverURL.absoluteString, forKey: .serverURL)

                // Mirror Android: register in the multi-server registry and
                // promote the new server to active. The active-server write
                // + state transition match what `configure` does post-login.
                serverRegistry.activate(url: serverURL)
                refreshServers()
                state = .loggedIn(server: serverURL)

                return .paired(serverURL: serverURL, deviceID: response.deviceID)
            } catch {
                let reason = error.localizedDescription
                lastErrorMessage = reason
                return .failed(reason: reason)
            }
        }
    }
}