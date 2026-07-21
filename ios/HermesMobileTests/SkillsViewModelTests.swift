import XCTest
@testable import HermesMobile

final class SkillsViewModelTests: XCTestCase {
    @MainActor
    func testGroupedSkillsNormalizesBlankCategoriesAndSortsRows() {
        let groups = SkillsViewModel.groupedSkills(for: [
            SkillSummary(name: "zed", category: " coding ", description: nil, path: nil),
            SkillSummary(name: "Alpha", category: "coding", description: nil, path: nil),
            SkillSummary(name: "loose", category: "   ", description: nil, path: nil),
            SkillSummary(name: nil, category: nil, description: nil, path: nil)
        ])

        XCTAssertEqual(groups.map(\.category), ["coding", "Uncategorized"])
        XCTAssertEqual(groups.first?.skills.map(\.name), ["Alpha", "zed"])
        XCTAssertEqual(groups.last?.skills.map { $0.name ?? "Unnamed Skill" }, ["loose", "Unnamed Skill"])
    }
}
