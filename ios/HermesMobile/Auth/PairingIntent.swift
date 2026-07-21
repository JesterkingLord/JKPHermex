import Foundation

/// v1.6.0+: mirror of the Android `PairingIntent` sealed class. Parsed result
/// of a URL that came out of either a QR code (the desktop's
/// `python -m jkp pair`) or a paste from the clipboard.
///
/// The desktop side's `mobile_pairing.build_pairing_url()` encodes:
///   {endpoint}/v1/pair/connect#{query}
///   where query = urlencode({"pair_id": ..., "token": ...})
///
/// It puts the credentials in the fragment so they never appear in HTTP
/// request lines or server access logs when the URL is opened in a
/// browser. Both query and fragment are accepted here — the QR scanner
/// reads the whole URL, including any fragment.
///
/// Three outcomes:
///   - `completePairing` → server URL + pair_id + token (call POST /v1/pair/complete)
///   - `serverURLOnly`   → just the URL (paste-and-go, no auto-pair)
///   - `invalid`         → malformed, unsupported scheme, or no host
enum PairingIntent: Equatable {
    case completePairing(serverURL: URL, pairID: String, token: String)
    case serverURLOnly(URL)
    case invalid(reason: String)

    static func == (lhs: PairingIntent, rhs: PairingIntent) -> Bool {
        switch (lhs, rhs) {
        case let (.completePairing(a, b, c), .completePairing(d, e, f)):
            return a == d && b == e && c == f
        case let (.serverURLOnly(a), .serverURLOnly(b)):
            return a == b
        case let (.invalid(a), .invalid(b)):
            return a == b
        default:
            return false
        }
    }
}

enum PairingIntentParser {

    private static let pairPath = "/v1/pair/connect"

    /// Parse a raw URL string. Tolerates extra whitespace and missing
    /// scheme (defaults to https). Returns `.invalid` for empty input,
    /// non-HTTP schemes, hosts that can't be reached, or cleartext-blocked
    /// targets outside the iOS cleartext allowlist (mirrors the
    /// `normalizedServerURL(from:)` rule in AuthManager).
    static func parse(_ raw: String) -> PairingIntent {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .invalid(reason: "Empty URL")
        }

        let withScheme: String
        if trimmed.contains("://") {
            withScheme = trimmed
        } else {
            withScheme = "https://\(trimmed)"
        }

        guard var components = URLComponents(string: withScheme) else {
            return .invalid(reason: "Could not parse URL")
        }

        // Drop any path/query/fragment from the BASE URL we keep; we'll
        // re-add a clean path below.
        let pathWasPairConnect = components.path == pairPath
        components.path = "/"
        components.query = nil
        components.fragment = nil

        guard let scheme = components.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              let host = components.host
        else {
            return .invalid(reason: "Unsupported scheme")
        }

        // Reject http:// to public hosts (mirrors iOS AuthManager.allowCleartext rule).
        if scheme == "http" && !Self.allowsCleartext(host) {
            return .invalid(reason: "Cleartext blocked for \(host)")
        }

        guard let baseURL = components.url else {
            return .invalid(reason: "Could not build URL")
        }

        // Try the query string first (more explicit intent), then the fragment.
        if let (pairID, token) = pathWasPairConnect
            ? credentials(in: components.queryItems)
            : nil
        {
            return .completePairing(serverURL: baseURL, pairID: pairID, token: token)
        }

        if let fragment = withScheme.fragment(using: .utf8),
           let (pairID, token) = parseFragmentCredentials(fragment)
        {
            return .completePairing(serverURL: baseURL, pairID: pairID, token: token)
        }

        return .serverURLOnly(baseURL)
    }

    private static func credentials(in items: [URLQueryItem]) -> (String, String)? {
        var pairID: String?
        var token: String?
        for item in items {
            switch item.name {
            case "pair_id":
                pairID = item.value?.trimmingCharacters(in: .whitespacesAndNewlines)
            case "token":
                token = item.value?.trimmingCharacters(in: .whitespacesAndNewlines)
            default:
                continue
            }
        }
        guard let pairID, !pairID.isEmpty, let token, !token.isEmpty else {
            return nil
        }
        return (pairID, token)
    }

    /// Parses a URL-encoded query string fragment like `pair_id=abc&token=xyz`
    /// into the same credential pair.
    private static func parseFragmentCredentials(_ fragment: String) -> (String, String)? {
        // URLComponents puts fragment content into the `fragment` field;
        // we have to re-parse it ourselves since URLQueryItem helpers
        // don't accept raw fragments.
        let pairs = fragment.split(separator: "&")
        var dict: [String: String] = [:]
        for pair in pairs {
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            let key = parts[0]
            let rawValue = parts[1]
                .replacingOccurrences(of: "+", with: " ")
            let decoded = rawValue.removingPercentEncoding ?? rawValue
            dict[key] = decoded
        }
        guard let pairID = dict["pair_id"]?.trimmingCharacters(in: .whitespacesAndNewlines),
              !pairID.isEmpty,
              let token = dict["token"]?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty
        else {
            return nil
        }
        return (pairID, token)
    }

    /// iOS cleartext allowlist, mirroring `CleartextPolicy` on Android:
    /// loopback, CGNAT (100.64.0.0/10), and private LAN ranges are OK;
    /// anything else must be https.
    static func allowsCleartext(_ host: String) -> Bool {
        let lowered = host.lowercased()
        if lowered == "localhost" || lowered == "127.0.0.1" || lowered == "::1" {
            return true
        }
        if lowered.hasPrefix("100.") {
            // Tailscale CGNAT: 100.64.0.0/10 → first octet 100, second 64-127.
            let parts = lowered.split(separator: ".")
            if parts.count == 4, let second = Int(parts[1]), (64...127).contains(second) {
                return true
            }
        }
        if lowered.hasPrefix("10.") || lowered.hasPrefix("192.168.") {
            return true
        }
        // 172.16.0.0/12 → second octet 16-31
        if lowered.hasPrefix("172.") {
            let parts = lowered.split(separator: ".")
            if parts.count == 4, let second = Int(parts[1]), (16...31).contains(second) {
                return true
            }
        }
        return false
    }
}