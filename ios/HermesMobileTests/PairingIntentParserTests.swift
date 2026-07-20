import XCTest
@testable import HermesMobile

final class PairingIntentParserTests: XCTestCase {

    // MARK: - Complete pairing (query-string form)

    func test_completePairing_fromFullURLWithPairIDAndTokenInQuery() throws {
        let raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=abc&token=xyz"
        let intent = PairingIntentParser.parse(raw)
        guard case let .completePairing(url, pairID, token) = intent else {
            XCTFail("expected completePairing, got \(intent)")
            return
        }
        XCTAssertEqual(url.host, "100.88.54.29")
        XCTAssertEqual(url.port, 8642)
        XCTAssertEqual(pairID, "abc")
        XCTAssertEqual(token, "xyz")
    }

    func test_completePairing_fromFragmentForm() throws {
        let raw = "http://100.88.54.29:8642/v1/pair/connect#pair_id=abc&token=xyz"
        let intent = PairingIntentParser.parse(raw)
        guard case let .completePairing(_, pairID, token) = intent else {
            XCTFail("expected completePairing, got \(intent)")
            return
        }
        XCTAssertEqual(pairID, "abc")
        XCTAssertEqual(token, "xyz")
    }

    func test_completePairing_queryTakesPrecedenceOverFragment() throws {
        let raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=Q&token=QT#pair_id=F&token=FT"
        let intent = PairingIntentParser.parse(raw)
        guard case let .completePairing(_, pairID, token) = intent else {
            XCTFail("expected completePairing, got \(intent)")
            return
        }
        XCTAssertEqual(pairID, "Q")
        XCTAssertEqual(token, "QT")
    }

    func test_completePairing_normalizesServerURL() throws {
        let raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=p&token=t"
        let intent = PairingIntentParser.parse(raw)
        guard case let .completePairing(url, _, _) = intent else {
            XCTFail("expected completePairing")
            return
        }
        XCTAssertEqual(url.path, "/")
        XCTAssertNil(url.query)
        XCTAssertNil(url.fragment)
    }

    // MARK: - ServerURLOnly fallback

    func test_serverURLOnly_whenURLHasNoPairPath() {
        let raw = "http://100.88.54.29:8642/some/other/path?foo=bar"
        let intent = PairingIntentParser.parse(raw)
        guard case let .serverURLOnly(url) = intent else {
            XCTFail("expected serverURLOnly, got \(intent)")
            return
        }
        XCTAssertEqual(url.host, "100.88.54.29")
    }

    func test_serverURLOnly_whenTokenMissing() {
        let raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=abc"
        guard case .serverURLOnly = PairingIntentParser.parse(raw) else {
            XCTFail("expected serverURLOnly")
            return
        }
    }

    func test_serverURLOnly_whenPairIDMissing() {
        let raw = "http://100.88.54.29:8642/v1/pair/connect?token=xyz"
        guard case .serverURLOnly = PairingIntentParser.parse(raw) else {
            XCTFail("expected serverURLOnly")
            return
        }
    }

    // MARK: - Defaults and tolerance

    func test_missingSchemeDefaultsToHTTPS() {
        let raw = "hermes.example.com/v1/pair/connect?pair_id=p&token=t"
        guard case let .completePairing(url, _, _) = PairingIntentParser.parse(raw) else {
            XCTFail("expected completePairing")
            return
        }
        XCTAssertEqual(url.scheme, "https")
        XCTAssertEqual(url.host, "hermes.example.com")
    }

    func test_leadingAndTrailingWhitespaceIsTrimmed() {
        let raw = "   http://100.88.54.29:8642/v1/pair/connect?pair_id=p&token=t   \n"
        guard case .completePairing = PairingIntentParser.parse(raw) else {
            XCTFail("expected completePairing")
            return
        }
    }

    // MARK: - Invalid

    func test_emptyStringIsInvalid() {
        guard case .invalid = PairingIntentParser.parse("") else {
            XCTFail("expected invalid")
            return
        }
    }

    func test_whitespaceOnlyIsInvalid() {
        guard case .invalid = PairingIntentParser.parse("   \t\n") else {
            XCTFail("expected invalid")
            return
        }
    }

    func test_unsupportedSchemeIsInvalid() {
        let raw = "ftp://hermes.example.com/v1/pair/connect?pair_id=p&token=t"
        guard case let .invalid(reason) = PairingIntentParser.parse(raw) else {
            XCTFail("expected invalid")
            return
        }
        XCTAssertTrue(reason.contains("scheme", options: .caseInsensitive),
                      "reason mentions scheme: \(reason)")
    }

    func test_unparseableStringIsInvalid() {
        guard case .invalid = PairingIntentParser.parse("not a url at all") else {
            XCTFail("expected invalid")
            return
        }
    }

    func test_cleartextToPublicHostIsInvalid() {
        let raw = "http://8.8.8.8:8000/"
        guard case let .invalid(reason) = PairingIntentParser.parse(raw) else {
            XCTFail("expected invalid")
            return
        }
        XCTAssertTrue(reason.contains("Cleartext", options: .caseInsensitive),
                      "reason mentions cleartext: \(reason)")
    }

    func test_cleartextToLocalhostIsAllowed() {
        guard case .serverURLOnly = PairingIntentParser.parse("http://127.0.0.1:8787/") else {
            XCTFail("expected serverURLOnly")
            return
        }
    }

    func test_cleartextToTailscaleIsAllowed() {
        let raw = "http://100.88.54.29:8642/v1/pair/connect?pair_id=p&token=t"
        guard case .completePairing = PairingIntentParser.parse(raw) else {
            XCTFail("expected completePairing")
            return
        }
    }

    // MARK: - Cleartext allowlist

    func test_cleartextAllowlist() {
        XCTAssertTrue(PairingIntentParser.allowsCleartext("127.0.0.1"))
        XCTAssertTrue(PairingIntentParser.allowsCleartext("localhost"))
        XCTAssertTrue(PairingIntentParser.allowsCleartext("100.88.54.29"))
        XCTAssertTrue(PairingIntentParser.allowsCleartext("100.120.5.6")) // 100.* / 64-127
        XCTAssertTrue(PairingIntentParser.allowsCleartext("10.0.0.5"))
        XCTAssertTrue(PairingIntentParser.allowsCleartext("192.168.1.1"))
        XCTAssertTrue(PairingIntentParser.allowsCleartext("172.20.0.1"))
        XCTAssertFalse(PairingIntentParser.allowsCleartext("8.8.8.8"))
        XCTAssertFalse(PairingIntentParser.allowsCleartext("172.32.0.1")) // outside /12
        XCTAssertFalse(PairingIntentParser.allowsCleartext("100.55.0.1")) // outside CGNAT
    }
}