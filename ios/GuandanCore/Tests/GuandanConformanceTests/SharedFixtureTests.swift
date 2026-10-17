import Foundation
import XCTest
import GuandanRules

/// Structural checks on the shared fixtures, read by path from the repository.
///
/// These checks deliberately avoid exact fixture contents (profile names, case
/// counts, protocol version), which change while the Kotlin side evolves. Real
/// behavioural conformance arrives with the WI-11 port.
final class SharedFixtureTests: XCTestCase {
    private static let fixtureRoot = "core/src/test/resources/fixtures"
    private static let fixtureFolders = ["format", "profiles", "rules", "wire"]
    /// Folders that must each hold at least one JSON fixture.
    private static let requiredNonEmpty = ["format", "rules", "wire"]

    func testRepositoryRootIsFound() throws {
        let root = try SharedFixtures.repoRoot()
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("settings.gradle.kts").path))
    }

    func testEveryFixtureFolderHasJsonFiles() throws {
        for folder in Self.requiredNonEmpty {
            let files = try SharedFixtures.files(in: "\(Self.fixtureRoot)/\(folder)", withExtension: "json")
            XCTAssertGreaterThan(files.count, 0, "\(folder) has no JSON fixtures")
        }
    }

    func testEveryFixtureJsonParses() throws {
        var parsed = 0
        for folder in Self.fixtureFolders {
            let relative = "\(Self.fixtureRoot)/\(folder)"
            let files: [URL]
            do {
                files = try SharedFixtures.files(in: relative, withExtension: "json")
            } catch where !Self.requiredNonEmpty.contains(folder) {
                continue // optional folder (e.g. profiles) may legitimately be empty
            }
            for file in files {
                let data = try Data(contentsOf: file)
                XCTAssertNoThrow(
                    try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
                    "Invalid JSON: \(relative)/\(file.lastPathComponent)"
                )
                parsed += 1
            }
        }
        XCTAssertGreaterThan(parsed, 0, "No fixture JSON was parsed")
    }

    func testRuleProfilesDecodeAndIdMatchesFileName() throws {
        let files = try SharedFixtures.files(in: "rules/profiles", withExtension: "json")
        XCTAssertGreaterThan(files.count, 0)
        let semver = try NSRegularExpression(pattern: #"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$"#)
        for file in files {
            let header = try JSONDecoder().decode(RuleProfileHeader.self, from: Data(contentsOf: file))
            let stem = file.deletingPathExtension().lastPathComponent
            XCTAssertEqual(header.id, stem, "Profile id must equal its file name: \(file.lastPathComponent)")
            let range = NSRange(header.version.startIndex..., in: header.version)
            XCTAssertNotNil(
                semver.firstMatch(in: header.version, range: range),
                "Version '\(header.version)' in \(file.lastPathComponent) is not MAJOR.MINOR.PATCH"
            )
            XCTAssertFalse(header.displayName.isEmpty, "Empty displayName in \(file.lastPathComponent)")
        }
    }

    func testGoldenRulesFixtureHasCases() throws {
        let url = try SharedFixtures.directory("\(Self.fixtureRoot)/rules")
            .appendingPathComponent("golden-v1.json")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        let cases: [Any]
        switch json {
        case let array as [Any]:
            cases = array
        case let object as [String: Any]:
            // Tolerate a wrapper object: prefer a "cases" key, otherwise the largest array value.
            if let named = object["cases"] as? [Any] {
                cases = named
            } else {
                cases = object.values.compactMap { $0 as? [Any] }.max { $0.count < $1.count } ?? []
            }
        default:
            return XCTFail("golden-v1.json is neither a JSON array nor an object")
        }
        XCTAssertFalse(cases.isEmpty, "golden-v1.json contains no cases")
    }

    func testGoldenNotesExist() throws {
        let notes = try SharedFixtures.files(in: "rules/golden", withExtension: "md")
        XCTAssertGreaterThan(notes.count, 0)
    }

    func testRulesEnginePortIsPendingWI11() throws {
        throw XCTSkip("Rules engine NOT ported to Swift: WI-11 (implementation plus a physical iPhone) is pending. Only fixture structure is checked.")
    }
}
