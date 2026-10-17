// swift-tools-version: 6.0
// Native Swift core for the iPhone client (gate G3: native Swift, not Kotlin Multiplatform).
// Zero third-party dependencies by design: Foundation and XCTest only.

import PackageDescription

let package = Package(
    name: "GuandanCore",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "GuandanCore", targets: ["GuandanRules", "GuandanWire"]),
    ],
    targets: [
        .target(name: "GuandanRules"),
        .target(name: "GuandanWire"),
        .testTarget(
            name: "GuandanConformanceTests",
            dependencies: ["GuandanRules", "GuandanWire"]
        ),
    ]
)
