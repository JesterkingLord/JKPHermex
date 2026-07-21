import Foundation

enum MemorySection: String, CaseIterable, Decodable, Encodable, Equatable, Identifiable {
    case memory
    case user
    case soul

    var id: String { rawValue }
}

struct MemoryResponse: Decodable, Equatable {
    let memory: String?
    let user: String?
    let soul: String?
    let memoryPath: String?
    let userPath: String?
    let soulPath: String?
    let memoryMtime: Double?
    let userMtime: Double?
    let soulMtime: Double?

    enum CodingKeys: String, CodingKey {
        case memory
        case user
        case soul
        case memoryPath
        case userPath
        case soulPath
        case memoryMtime
        case userMtime
        case soulMtime
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        memory = try container.decodeIfPresent(String.self, forKey: .memory)
        user = try container.decodeIfPresent(String.self, forKey: .user)
        soul = try container.decodeIfPresent(String.self, forKey: .soul)
        memoryPath = try container.decodeIfPresent(String.self, forKey: .memoryPath)
        userPath = try container.decodeIfPresent(String.self, forKey: .userPath)
        soulPath = try container.decodeIfPresent(String.self, forKey: .soulPath)
        memoryMtime = try container.decodeFlexibleDoubleIfPresent(forKey: .memoryMtime)
        userMtime = try container.decodeFlexibleDoubleIfPresent(forKey: .userMtime)
        soulMtime = try container.decodeFlexibleDoubleIfPresent(forKey: .soulMtime)
    }
}

struct MemoryWriteResponse: Decodable, Equatable {
    let ok: Bool?
    let section: MemorySection?
    let path: String?
    let error: String?

    enum CodingKeys: String, CodingKey {
        case ok
        case section
        case path
        case error
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        ok = try container.decodeIfPresent(Bool.self, forKey: .ok)
        if let rawSection = try container.decodeIfPresent(String.self, forKey: .section) {
            section = MemorySection(rawValue: rawSection)
        } else {
            section = nil
        }
        path = try container.decodeIfPresent(String.self, forKey: .path)
        error = try container.decodeIfPresent(String.self, forKey: .error)
    }
}
