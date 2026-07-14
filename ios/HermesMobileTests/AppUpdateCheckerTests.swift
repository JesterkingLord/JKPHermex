import XCTest
@testable import HermesMobile

/// Unit tests for the GitHub update-check pieces that have interesting
/// logic: the JSON decoder, the asset filter, and the [SemanticVersion]
/// comparator.
///
/// The HTTP layer of `AppUpdateChecker` is one async call against
/// GitHub's API; testing it would be testing URLSession. Production runs
/// hit `api.github.com` unauthenticated.
///
/// v1.5.0.
final class AppUpdateCheckerTests: XCTestCase {

    // MARK: - JSON decode

    func testParsesRealisticReleasePayload() throws {
        let json = """
        {
          "tag_name": "v0.2.0",
          "name": "JKP Mobile v0.2.0",
          "body": "## What's new\\n- Rebrand to JKP Mobile\\n- GitHub update check",
          "html_url": "https://github.com/JesterkingLord/JKPHermex/releases/tag/v0.2.0",
          "assets": [
            {
              "name": "JKPHermex-v0.2.0-debug.apk",
              "browser_download_url": "https://github.com/.../JKPHermex-v0.2.0-debug.apk",
              "size": 12345678
            }
          ]
        }
        """
        let data = Data(json.utf8)
        let release = try JSONDecoder().decode(GitHubRelease.self, from: data)
        XCTAssertEqual(release.tagName, "v0.2.0")
        XCTAssertEqual(release.name, "JKP Mobile v0.2.0")
        XCTAssertTrue(release.body.contains("Rebrand"))
        XCTAssertEqual(release.assets.count, 1)
        XCTAssertEqual(release.assets[0].name, "JKPHermex-v0.2.0-debug.apk")
        XCTAssertEqual(release.assets[0].sizeBytes, 12345678)
        XCTAssertEqual(release.apkAsset?.sizeMB, "12.3 MB")
    }

    func testToleratesUnknownFields() throws {
        let json = """
        {
          "tag_name": "v0.2.0", "name": "x", "body": "", "html_url": "",
          "draft": false, "prerelease": false, "created_at": "2026-07-14T19:00:00Z",
          "author": { "login": "someone" },
          "assets": []
        }
        """
        let data = Data(json.utf8)
        let release = try JSONDecoder().decode(GitHubRelease.self, from: data)
        XCTAssertEqual(release.tagName, "v0.2.0")
        XCTAssertTrue(release.assets.isEmpty)
        XCTAssertNil(release.apkAsset)
    }

    func testApkAssetMatchesCaseInsensitively() throws {
        let json = """
        { "tag_name": "v0.2.0", "name": "x", "body": "", "html_url": "",
          "assets": [
            { "name": "checksums.txt", "browser_download_url": "a", "size": 100 },
            { "name": "JKP.APK", "browser_download_url": "b", "size": 200 }
          ] }
        """
        let data = Data(json.utf8)
        let release = try JSONDecoder().decode(GitHubRelease.self, from: data)
        XCTAssertEqual(release.apkAsset?.name, "JKP.APK")
    }

    // MARK: - SemanticVersion

    func testSemverParsesPlainThreePart() {
        let v = SemanticVersion.parse("0.1.0")
        XCTAssertEqual(v?.major, 0)
        XCTAssertEqual(v?.minor, 1)
        XCTAssertEqual(v?.patch, 0)
        XCTAssertNil(v?.preRelease)
    }

    func testSemverParsesVPrefix() {
        let v = SemanticVersion.parse("v1.2.3")
        XCTAssertEqual(v?.major, 1)
        XCTAssertEqual(v?.minor, 2)
        XCTAssertEqual(v?.patch, 3)
    }

    func testSemverParsesPreRelease() {
        let v = SemanticVersion.parse("v0.2.0-rc1")
        XCTAssertEqual(v?.preRelease, "rc1")
    }

    func testSemverRejectsGarbage() {
        XCTAssertNil(SemanticVersion.parse(""))
        XCTAssertNil(SemanticVersion.parse("1"))
        XCTAssertNil(SemanticVersion.parse("1.2"))
        XCTAssertNil(SemanticVersion.parse("1.2.3.4"))
        XCTAssertNil(SemanticVersion.parse("a.b.c"))
        XCTAssertNil(SemanticVersion.parse("not a version"))
    }

    func testIsNewerThanMajorWins() {
        XCTAssertTrue(SemanticVersion.parse("2.0.0")!.isNewerThan(SemanticVersion.parse("1.9.9")!))
    }

    func testIsNewerThanMinorWins() {
        XCTAssertTrue(SemanticVersion.parse("0.2.0")!.isNewerThan(SemanticVersion.parse("0.1.99")!))
    }

    func testIsNewerThanPatchWins() {
        XCTAssertTrue(SemanticVersion.parse("0.1.1")!.isNewerThan(SemanticVersion.parse("0.1.0")!))
    }

    func testIsNewerThanReleaseBeatsRc() {
        let release = SemanticVersion.parse("0.2.0")!
        let rc = SemanticVersion.parse("0.2.0-rc1")!
        XCTAssertTrue(release.isNewerThan(rc))
        XCTAssertFalse(rc.isNewerThan(release))
    }

    func testIsNewerThanEqualIsFalse() {
        let a = SemanticVersion.parse("0.1.0")!
        let b = SemanticVersion.parse("0.1.0")!
        XCTAssertFalse(a.isNewerThan(b))
        XCTAssertFalse(b.isNewerThan(a))
    }
}