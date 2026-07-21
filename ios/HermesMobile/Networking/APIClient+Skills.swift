import Foundation

extension APIClient {
    func skills() async throws -> SkillsResponse {
        try await send(endpoint: .skills, method: "GET")
    }

    func skillContent(name: String, file: String? = nil) async throws -> SkillDetailResponse {
        try await send(endpoint: .skillContent(name: name, file: file), method: "GET")
    }
}

