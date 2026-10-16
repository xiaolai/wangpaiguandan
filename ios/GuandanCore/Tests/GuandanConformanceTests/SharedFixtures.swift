import Foundation

/// Locates the shared fixtures BY PATH in the repository checkout.
///
/// Fixtures are never copied into `ios/`: the Kotlin modules and this package
/// read the same files, so a divergence cannot hide behind a stale copy.
enum SharedFixtures {
    struct LocateError: Error, CustomStringConvertible {
        let description: String
    }

    /// Walks up from this source file until a directory containing
    /// `settings.gradle.kts` (the repository root) is found. Throws if none is.
    static func repoRoot(from filePath: String = #filePath) throws -> URL {
        let fm = FileManager.default
        var dir = URL(fileURLWithPath: filePath).deletingLastPathComponent()
        while dir.path != "/" {
            if fm.fileExists(atPath: dir.appendingPathComponent("settings.gradle.kts").path) {
                return dir
            }
            dir = dir.deletingLastPathComponent()
        }
        throw LocateError(description: "No settings.gradle.kts found above \(filePath); cannot locate repository root")
    }

    static func directory(_ relativePath: String) throws -> URL {
        let url = try repoRoot().appendingPathComponent(relativePath, isDirectory: true)
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue else {
            throw LocateError(description: "Fixture directory missing: \(relativePath)")
        }
        return url
    }

    /// All files with `pathExtension` under `relativePath` (recursive), sorted by path.
    /// Throws if the directory is missing or holds no such file: an empty scan never passes.
    static func files(in relativePath: String, withExtension pathExtension: String) throws -> [URL] {
        let dir = try directory(relativePath)
        guard let enumerator = FileManager.default.enumerator(
            at: dir,
            includingPropertiesForKeys: [.isRegularFileKey]
        ) else {
            throw LocateError(description: "Cannot enumerate \(relativePath)")
        }
        var found: [URL] = []
        for case let url as URL in enumerator where url.pathExtension == pathExtension {
            found.append(url)
        }
        guard !found.isEmpty else {
            throw LocateError(description: "No *.\(pathExtension) files under \(relativePath): refusing to pass on an empty scan")
        }
        return found.sorted { $0.path < $1.path }
    }
}
