import Foundation

struct WorkspacesResponse: Decodable, Equatable {
    let workspaces: [WorkspaceRoot]?
    let last: String?
}

struct WorkspaceSuggestionsResponse: Decodable, Equatable {
    let suggestions: [String]?
    let prefix: String?
}

struct WorkspaceRoot: Decodable, Equatable, Sendable {
    let path: String?
    let name: String?

    enum CodingKeys: String, CodingKey {
        case path
        case name
    }

    init(from decoder: Decoder) throws {
        if let stringValue = try? decoder.singleValueContainer().decode(String.self) {
            path = stringValue
            name = nil
            return
        }

        let container = try decoder.container(keyedBy: CodingKeys.self)
        path = try container.decodeIfPresent(String.self, forKey: .path)
        name = try container.decodeIfPresent(String.self, forKey: .name)
    }
}

struct DirectoryListResponse: Decodable, Equatable {
    let entries: [WorkspaceEntry]?
    let path: String?
    let workspace: String?
    let error: String?
}

struct WorkspaceEntry: Decodable, Equatable, Identifiable {
    var id: String { path ?? name ?? UUID().uuidString }
    var isBrowsableDirectory: Bool {
        isDirectory == true || type == "dir"
    }

    let name: String?
    let path: String?
    let type: String?
    let size: Int?
    let modified: Double?
    let isDirectory: Bool?

    enum CodingKeys: String, CodingKey {
        case name
        case path
        case type
        case size
        case modified
        case isDirectory
        case isDir
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decodeIfPresent(String.self, forKey: .name)
        path = try container.decodeIfPresent(String.self, forKey: .path)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        size = try container.decodeIfPresent(Int.self, forKey: .size)
        modified = try container.decodeIfPresent(Double.self, forKey: .modified)
        isDirectory = try container.decodeIfPresent(Bool.self, forKey: .isDirectory)
            ?? container.decodeIfPresent(Bool.self, forKey: .isDir)
    }
}

struct FileResponse: Decodable, Equatable {
    let content: String?
    let path: String?
    let name: String?
    let language: String?
    let size: Int?
    let lines: Int?
    let error: String?
}
