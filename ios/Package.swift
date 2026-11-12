// swift-tools-version:5.9
// KairoCore: the protocol for iOS. On Apple platforms it uses CryptoKit; elsewhere (Linux CI, WSL) swift-crypto,
// which has the same API, so the core is built and tested off a Mac too.
import PackageDescription

let package = Package(
    name: "KairoMesh",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "KairoCore", targets: ["KairoCore"])],
    dependencies: [.package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"4.0.0")],
    targets: [
        .target(name: "KairoCore", dependencies: [
            .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux, .windows])),
        ]),
        .testTarget(name: "KairoCoreTests", dependencies: ["KairoCore"]),
    ]
)
