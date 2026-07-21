import XCTest
import AVFoundation
import ImageIO
import SwiftData
import UIKit
import UniformTypeIdentifiers
@testable import HermesMobile

final class APIClientSkillEndpointTests: APIClientTestCase {
    func testSkillsBuildsExpectedPathAndDecodesResponse() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/skills")
            XCTAssertEqual(request.httpMethod, "GET")

            return apiTestJSONResponse("""
            {
              "skills": [
                {"name": "swift-refactor", "category": "coding", "description": "Refactors Swift code", "path": "/skills/coding/swift-refactor"},
                {"name": "doc-search", "category": "research", "description": "Searches docs"}
              ]
            }
            """, for: request)
        }

        let response = try await client.skills()
        XCTAssertEqual(response.skills?.count, 2)
        XCTAssertEqual(response.skills?[0].name, "swift-refactor")
        XCTAssertEqual(response.skills?[0].category, "coding")
        XCTAssertEqual(response.skills?[0].description, "Refactors Swift code")
        XCTAssertEqual(response.skills?[1].name, "doc-search")
        XCTAssertEqual(response.skills?[1].category, "research")
    }

    func testSkillsToleratesMissingFields() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/skills")
            return apiTestJSONResponse("""
            {"skills": [{"name": "minimal-skill"}]}
            """, for: request)
        }

        let response = try await client.skills()
        XCTAssertEqual(response.skills?.count, 1)
        XCTAssertEqual(response.skills?[0].name, "minimal-skill")
        XCTAssertNil(response.skills?[0].category)
        XCTAssertNil(response.skills?[0].description)
    }

    func testSkillContentBuildsExpectedQueryAndDecodesResponse() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/skills/content")
            let components = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)
            let query = Dictionary(uniqueKeysWithValues: (components?.queryItems ?? []).map { ($0.name, $0.value) })
            XCTAssertEqual(query["name"], "swift-refactor")
            XCTAssertNil(query["file"])
            XCTAssertEqual(request.httpMethod, "GET")

            return apiTestJSONResponse("""
            {"name": "swift-refactor", "content": "# Swift Refactor\\n\\nRefactors Swift code.", "linked_files": {"README.md": "Read me", "config.json": "Config"}}
            """, for: request)
        }

        let response = try await client.skillContent(name: "swift-refactor")
        XCTAssertEqual(response.name, "swift-refactor")
        XCTAssertEqual(response.content, "# Swift Refactor\n\nRefactors Swift code.")
        XCTAssertEqual(response.linkedFiles, ["README.md", "config.json"])
    }

    func testSkillContentDecodesGroupedLinkedFiles() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/skills/content")

            return apiTestJSONResponse("""
            {
              "name": "google-workspace",
              "content": "# Google Workspace",
              "linked_files": {
                "references": ["references/auth.md"],
                "scripts": ["scripts/setup.py", "scripts/google_api.py"],
                "assets": []
              }
            }
            """, for: request)
        }

        let response = try await client.skillContent(name: "google-workspace")

        XCTAssertEqual(response.linkedFiles, [
            "references/auth.md",
            "scripts/google_api.py",
            "scripts/setup.py"
        ])
    }

    func testSkillLinkedFileBuildsExpectedQueryAndDecodesResponse() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/skills/content")
            let components = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)
            let query = Dictionary(uniqueKeysWithValues: (components?.queryItems ?? []).map { ($0.name, $0.value) })
            XCTAssertEqual(query["name"], "swift-refactor")
            XCTAssertEqual(query["file"], "README.md")
            XCTAssertEqual(request.httpMethod, "GET")

            return apiTestJSONResponse("""
            {"content": "# README\\n\\nDetails here.", "path": "README.md"}
            """, for: request)
        }

        let response = try await client.skillContent(name: "swift-refactor", file: "README.md")
        XCTAssertEqual(response.content, "# README\n\nDetails here.")
    }
}
