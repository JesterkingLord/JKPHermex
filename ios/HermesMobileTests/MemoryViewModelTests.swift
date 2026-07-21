import XCTest
import AVFoundation
import ImageIO
import SwiftData
import UIKit
import UniformTypeIdentifiers
@testable import HermesMobile

final class MemoryViewModelTests: XCTestCase {
    override func tearDown() {
        MockURLProtocol.requestHandler = nil
        super.tearDown()
    }

    @MainActor
    func testSaveWritesSelectedSectionAndReloadsMemory() async throws {
        var requestPaths: [String] = []
        let client = makeClient { request in
            requestPaths.append(request.url?.path ?? "")

            if request.url?.path == "/api/memory/write" {
                XCTAssertEqual(request.httpMethod, "POST")
                let data = try XCTUnwrap(apiTestBodyData(from: request))
                let body = try JSONSerialization.jsonObject(with: data) as? [String: Any]
                XCTAssertEqual(body?["section"] as? String, "soul")
                XCTAssertEqual(body?["content"] as? String, "# Updated Soul")

                return apiTestJSONResponse("""
                {
                  "ok": true,
                  "section": "soul",
                  "path": "/Users/test/.hermes/SOUL.md"
                }
                """, for: request)
            }

            XCTAssertEqual(request.url?.path, "/api/memory")
            return apiTestJSONResponse("""
            {
              "memory": "# Notes",
              "user": "# Profile",
              "soul": "# Updated Soul",
              "memory_mtime": 1770000000,
              "user_mtime": 1770000100,
              "soul_mtime": 1770000200
            }
            """, for: request)
        }
        let viewModel = MemoryViewModel(
            server: try XCTUnwrap(URL(string: "https://example.test")),
            client: client
        )

        let didSave = await viewModel.save(section: .soul, content: "# Updated Soul")

        XCTAssertTrue(didSave)
        XCTAssertEqual(requestPaths, ["/api/memory/write", "/api/memory"])
        XCTAssertEqual(viewModel.memoryText, "# Notes")
        XCTAssertEqual(viewModel.userText, "# Profile")
        XCTAssertEqual(viewModel.soulText, "# Updated Soul")
        XCTAssertEqual(viewModel.soulMtime, Date(timeIntervalSince1970: 1_770_000_200))
        XCTAssertTrue(viewModel.hasLoaded)
        XCTAssertNil(viewModel.actionErrorMessage)
    }

    @MainActor
    func testSaveSurfacesRejectedResponseWithoutReloading() async throws {
        var requestPaths: [String] = []
        let client = makeClient { request in
            requestPaths.append(request.url?.path ?? "")

            return apiTestJSONResponse("""
            {
              "ok": false,
              "error": "section rejected"
            }
            """, for: request)
        }
        let viewModel = MemoryViewModel(
            server: try XCTUnwrap(URL(string: "https://example.test")),
            client: client
        )

        let didSave = await viewModel.save(section: .memory, content: "# Notes")

        XCTAssertFalse(didSave)
        XCTAssertEqual(requestPaths, ["/api/memory/write"])
        XCTAssertEqual(viewModel.actionErrorMessage, "section rejected")
        XCTAssertFalse(viewModel.hasLoaded)
    }

    private func makeClient(
        handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)
    ) -> APIClient {
        MockURLProtocol.requestHandler = handler

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: configuration)

        return APIClient(baseURL: URL(string: "https://example.test")!, session: session)
    }
}
