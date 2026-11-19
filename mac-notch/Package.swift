// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "HorizonNotch",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "HorizonNotch",
            path: "Sources/HorizonNotch",
            linkerSettings: [
                .linkedFramework("IOKit"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("ServiceManagement"),
            ]
        ),
    ]
)
