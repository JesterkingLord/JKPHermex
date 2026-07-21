import Foundation

/// Subset of `GET /repos/{owner}/{repo}/releases/latest` we use for the
/// in-app update check.
///
/// GitHub's full release payload is ~30 fields; this struct decodes only
/// what the [AppUpdateChecker] renders (version tag, release notes, APK
/// download URL). `Codable` because the rest of the app's networking
/// already uses `JSONDecoder` rather than `Serialization`.
///
/// v1.5.0: mirrors the Android `GitHubRelease.kt` so the two clients
/// compare against the same shape on the same GitHub release.
struct GitHubRelease: Codable, Equatable {
    let tagName: String
    let name: String
    let body: String
    let htmlURL: String
    let assets: [GitHubReleaseAsset]

    /// The first `.apk` asset, if any. Filenames are matched
    /// case-insensitively so `JKP_Mobile.apk` and `jkp.apk` both count.
    var apkAsset: GitHubReleaseAsset? {
        assets.first { $0.name.lowercased().hasSuffix(".apk") }
    }

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name"
        case name
        case body
        case htmlURL = "html_url"
        case assets
    }
}

struct GitHubReleaseAsset: Codable, Equatable {
    let name: String
    let browserDownloadURL: String
    let sizeBytes: Int

    enum CodingKeys: String, CodingKey {
        case name
        case browserDownloadURL = "browser_download_url"
        case sizeBytes = "size"
    }

    /// "12.3 MB" — pre-formatted so the view layer doesn't reach into
    /// `ByteCountFormatter` for one value.
    var sizeMB: String {
        let mb = Double(sizeBytes) / 1_000_000.0
        return String(format: "%.1f MB", mb)
    }
}

/// Minimal `MAJOR.MINOR.PATCH[-PRERELEASE]` parser mirroring the
/// Android `SemanticVersion` so the two clients agree on ordering.
///
/// Rules:
///  - Higher major wins.
///  - Tied major → higher minor wins.
///  - Tied minor → higher patch wins.
///  - Tied numeric → a release (no pre-release tag) beats the same
///    numeric version with a pre-release tag (`0.2.0 > 0.2.0-rc1`).
///
/// Not full SemVer 2.0.0 — no build metadata, no complex pre-release
/// ordering (alpha < beta < rc). Returns nil on garbage.
struct SemanticVersion: Equatable {
    let major: Int
    let minor: Int
    let patch: Int
    let preRelease: String?

    func isNewerThan(_ other: SemanticVersion) -> Bool {
        if major != other.major { return major > other.major }
        if minor != other.minor { return minor > other.minor }
        if patch != other.patch { return patch > other.patch }
        // Same X.Y.Z — whichever has no pre-release wins.
        let aHas = preRelease?.isEmpty == false
        let bHas = other.preRelease?.isEmpty == false
        if !aHas && bHas { return true }
        if aHas && !bHas { return false }
        return false
    }

    static func parse(_ raw: String) -> SemanticVersion? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.lowercased().hasPrefix("v") { s.removeFirst() }
        guard !s.isEmpty else { return nil }

        let dashIdx = s.firstIndex(of: "-")
        let numericPart: String
        let preRelease: String?
        if let idx = dashIdx {
            numericPart = String(s[..<idx])
            let rest = String(s[s.index(after: idx)...])
            preRelease = rest.isEmpty ? nil : rest
        } else {
            numericPart = s
            preRelease = nil
        }

        let parts = numericPart.split(separator: ".", maxSplits: 3, omittingEmptySubsequences: false)
        guard parts.count == 3,
              let major = Int(parts[0]),
              let minor = Int(parts[1]),
              let patch = Int(parts[2]),
              major >= 0, minor >= 0, patch >= 0
        else { return nil }
        return SemanticVersion(major: major, minor: minor, patch: patch, preRelease: preRelease)
    }
}

/// Outcome of a `check()` call. Sealed so the view layer renders each
/// branch distinctly — never collapsed into a single "success/failure" bool.
enum AppUpdateResult: Equatable {
    /// Remote is newer than what's installed.
    case updateAvailable(currentVersion: String, latestVersion: String, release: GitHubRelease)
    /// Remote matches or is older than what's installed.
    case upToDate(currentVersion: String, latestVersion: String?)
    /// Network call failed or returned an unparseable payload.
    case failed(reason: String)
}

/// Fetches the latest release from a GitHub repo and compares it against
/// the installed app version (`Bundle.main.infoDictionary`).
///
/// GitHub's `/releases/latest` endpoint is unauthenticated for public repos,
/// rate-limited to 60 requests/hour per IP. For an app the operator opens a
/// few times a day that's plenty. The session uses a 10s total timeout so a
/// stuck network call doesn't leave the settings screen looking frozen.
///
/// Unverified on a Mac from this Windows host — pattern is the same as
/// `APIClient` (typed actor, isolated session, escape failures into
/// `AppUpdateResult.failed`) so the integration is straightforward when
/// `xcodebuild test` becomes available.
actor AppUpdateChecker {
    private let owner: String
    private let repo: String
    private let session: URLSession

    init(owner: String, repo: String, session: URLSession = .shared) {
        self.owner = owner
        self.repo = repo
        self.session = session
    }

    /// Compares the installed version against the latest remote release.
    /// - Returns: an `AppUpdateResult` the view layer can render directly.
    func check() async -> AppUpdateResult {
        let currentVersion = Self.installedVersionName()
        guard let currentVersion else {
            return .failed(reason: "Couldn't determine the installed app version.")
        }
        return await checkAgainst(currentVersion: currentVersion)
    }

    /// Pure compare (no Bundle lookup) — useful for previews + tests.
    func checkAgainst(currentVersion: String) async -> AppUpdateResult {
        guard let release = await fetchLatest() else {
            return .failed(reason: "Couldn't reach GitHub to check for updates.")
        }
        let current = SemanticVersion.parse(currentVersion)
        let latest = SemanticVersion.parse(stripVPrefix(release.tagName))
        if current == nil || latest == nil {
            return .upToDate(currentVersion: currentVersion, latestVersion: release.tagName)
        }
        if let latest, latest.isNewerThan(current!) {
            return .updateAvailable(
                currentVersion: currentVersion,
                latestVersion: release.tagName,
                release: release
            )
        }
        return .upToDate(currentVersion: currentVersion, latestVersion: release.tagName)
    }

    private func fetchLatest() async -> GitHubRelease? {
        let url = URL(string: "https://api.github.com/repos/\(owner)/\(repo)/releases/latest")!
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("JKPMobile-iOS/\(owner)", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                return nil
            }
            let decoder = JSONDecoder()
            return try? decoder.decode(GitHubRelease.self, from: data)
        } catch {
            return nil
        }
    }

    private func stripVPrefix(_ tag: String) -> String {
        tag.hasPrefix("v") || tag.hasPrefix("V") ? String(tag.dropFirst()) : tag
    }

    static func installedVersionName() -> String? {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
    }
}