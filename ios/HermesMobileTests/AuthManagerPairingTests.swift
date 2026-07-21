import XCTest
@testable import HermesMobile

@MainActor
final class AuthManagerPairingTests: XCTestCase {

    private static let pairingURL =
        "http://100.88.54.29:8642/v1/pair/connect?pair_id=p1&token=t-secret"

    func test_pairAndConfigure_pairedFillsStateAndPersistsGrant() async throws {
        let keychain = InMemoryKeychainStore()
        let client = MockAuthAPIClient(
            authStatus: AuthStatusResponse(authEnabled: false, loggedIn: false),
            pairBehavior: .init(
                response: PairCompleteResponse(
                    pairID: "p1",
                    deviceID: "dev-1",
                    grant: "g-secret",
                    tokenType: "Bearer"
                )
            )
        )
        let manager = AuthManager(keychain: keychain, clientFactory: { _ in client })

        let outcome = await manager.pairAndConfigure(
            rawText: Self.pairingURL,
            deviceName: "Test phone"
        )

        guard case let .paired(serverURL, deviceID) = outcome else {
            XCTFail("expected .paired, got \(outcome)")
            return
        }
        XCTAssertEqual(serverURL.host, "100.88.54.29")
        XCTAssertEqual(deviceID, "dev-1")
        if case .loggedIn(let active) = manager.state {
            XCTAssertEqual(active.host, "100.88.54.29")
        } else {
            XCTFail("expected loggedIn, got \(manager.state)")
        }

        // Pair call was made with the right credentials.
        XCTAssertEqual(client.pairCalls.count, 1)
        XCTAssertEqual(client.pairCalls.first?.pairID, "p1")
        XCTAssertEqual(client.pairCalls.first?.token, "t-secret")
        XCTAssertEqual(client.pairCalls.first?.deviceName, "Test phone")

        // Grant + device id are persisted per-server; server URL is saved.
        XCTAssertEqual(
            try keychain.load(.pairGrant, scope: "http://100.88.54.29:8642/"),
            "g-secret"
        )
        XCTAssertEqual(
            try keychain.load(.pairDeviceID, scope: "http://100.88.54.29:8642/"),
            "dev-1"
        )
        XCTAssertNotNil(try keychain.load(.serverURL))
    }

    func test_pairAndConfigure_prefilledFillsURLButDoesNotPair() async throws {
        let keychain = InMemoryKeychainStore()
        let client = MockAuthAPIClient(
            authStatus: AuthStatusResponse(authEnabled: false, loggedIn: false)
        )
        let manager = AuthManager(keychain: keychain, clientFactory: { _ in client })

        let outcome = await manager.pairAndConfigure(
            rawText: "http://hermes.example.com/api/sessions"
        )

        guard case let .prefilled(serverURL) = outcome else {
            XCTFail("expected .prefilled, got \(outcome)")
            return
        }
        XCTAssertEqual(serverURL.host, "hermes.example.com")
        XCTAssertEqual(client.pairCalls.count, 0)
        // Unconfigured — we never paired, so state stays as it was.
        if case .unconfigured = manager.state {
            // expected
        } else {
            XCTFail("expected unconfigured, got \(manager.state)")
        }
    }

    func test_pairAndConfigure_failedSurfacesReason() async {
        let keychain = InMemoryKeychainStore()
        let client = MockAuthAPIClient(
            authStatus: AuthStatusResponse(authEnabled: false, loggedIn: false),
            pairBehavior: .init(
                response: PairCompleteResponse(
                    pairID: "p", deviceID: "dev", grant: "g", tokenType: "Bearer"
                ),
                error: APIError.http(statusCode: 410, body: "pairing token expired")
            )
        )
        let manager = AuthManager(keychain: keychain, clientFactory: { _ in client })

        let outcome = await manager.pairAndConfigure(rawText: Self.pairingURL)
        guard case let .failed(reason) = outcome else {
            XCTFail("expected .failed, got \(outcome)")
            return
        }
        XCTAssertTrue(reason.lowercased().contains("expired"))
        XCTAssertNotNil(manager.lastErrorMessage)
        // Nothing was persisted.
        XCTAssertNil(try keychain.load(.serverURL))
        XCTAssertNil(try keychain.load(.pairGrant, scope: "http://100.88.54.29:8642/"))
    }

    func test_pairAndConfigure_emptyGrantReturnsFailed() async throws {
        let keychain = InMemoryKeychainStore()
        let client = MockAuthAPIClient(
            authStatus: AuthStatusResponse(authEnabled: false, loggedIn: false),
            pairBehavior: .init(
                response: PairCompleteResponse(
                    pairID: "p", deviceID: "", grant: "", tokenType: "Bearer"
                )
            )
        )
        let manager = AuthManager(keychain: keychain, clientFactory: { _ in client })

        let outcome = await manager.pairAndConfigure(rawText: Self.pairingURL)
        guard case let .failed(reason) = outcome else {
            XCTFail("expected .failed, got \(outcome)")
            return
        }
        XCTAssertTrue(reason.lowercased().contains("grant"))
        XCTAssertNil(try keychain.load(.serverURL))
    }

    func test_pairAndConfigure_invalidInputReturnsFailed() async {
        let keychain = InMemoryKeychainStore()
        let client = MockAuthAPIClient(
            authStatus: AuthStatusResponse(authEnabled: false, loggedIn: false)
        )
        let manager = AuthManager(keychain: keychain, clientFactory: { _ in client })

        let outcome = await manager.pairAndConfigure(rawText: "not a url")
        guard case let .failed(reason) = outcome else {
            XCTFail("expected .failed, got \(outcome)")
            return
        }
        XCTAssertFalse(reason.isEmpty)
    }

    func test_signOut_clearsPairGrantAndDeviceID() async throws {
        let keychain = InMemoryKeychainStore()
        let client = MockAuthAPIClient(
            authStatus: AuthStatusResponse(authEnabled: false, loggedIn: false),
            pairBehavior: .init(
                response: PairCompleteResponse(
                    pairID: "p", deviceID: "dev", grant: "g", tokenType: "Bearer"
                )
            )
        )
        let manager = AuthManager(keychain: keychain, clientFactory: { _ in client })

        let outcome = await manager.pairAndConfigure(rawText: Self.pairingURL)
        guard case .paired = outcome else {
            XCTFail("expected paired")
            return
        }

        XCTAssertEqual(
            try keychain.load(.pairGrant, scope: "http://100.88.54.29:8642/"),
            "g"
        )

        await manager.signOut()

        // All per-server pairing state is gone.
        XCTAssertNil(try keychain.load(.serverURL))
        XCTAssertNil(try keychain.load(.pairGrant, scope: "http://100.88.54.29:8642/"))
        XCTAssertNil(try keychain.load(.pairDeviceID, scope: "http://100.88.54.29:8642/"))
    }
}