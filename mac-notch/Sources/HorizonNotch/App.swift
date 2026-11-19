import AppKit
import ServiceManagement
import SwiftUI

@main
@MainActor
enum HorizonNotchMain {
    static let delegate = AppDelegate()

    static func main() {
        let app = NSApplication.shared
        app.delegate = delegate
        app.setActivationPolicy(.accessory) // no Dock icon
        app.run()
    }
}

/// A borderless panel that sits over the notch, above the menu bar, on every Space.
final class NotchPanel: NSPanel {
    init(frame: NSRect) {
        super.init(contentRect: frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        level = NSWindow.Level(rawValue: NSWindow.Level.mainMenu.rawValue + 3)
        collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        isMovable = false
        ignoresMouseEvents = true
        hidesOnDeactivate = false
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

/// Lets the first click on a button work even though the panel never activates the app.
final class ClickThroughHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = NotchModel()
    private var panel: NotchPanel?
    private var statusItem: NSStatusItem?
    private var mouseTimer: Timer?
    private var hoverSince: Date?
    private var leftSince: Date?

    /// Room the panel reserves; the island is drawn inside it at the top centre.
    private let panelSize = CGSize(width: 760, height: 320)

    func applicationDidFinishLaunching(_ notification: Notification) {
        model.start()
        placePanel()
        makeStatusItem()

        NotificationCenter.default.addObserver(
            self, selector: #selector(screensChanged),
            name: NSApplication.didChangeScreenParametersNotification, object: nil
        )

        // Hover tracking. Polling the pointer is cheap and needs no Accessibility permission.
        mouseTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.trackMouse() }
        }
    }

    @objc private func screensChanged() { placePanel() }

    /// The screen with a notch, else the main one.
    private var targetScreen: NSScreen? {
        NSScreen.screens.first { $0.safeAreaInsets.top > 0 } ?? NSScreen.main
    }

    private func placePanel() {
        guard let screen = targetScreen else { return }
        model.measure(screen: screen)
        let frame = NSRect(
            x: screen.frame.midX - panelSize.width / 2,
            y: screen.frame.maxY - panelSize.height,
            width: panelSize.width,
            height: panelSize.height
        )
        if let panel {
            panel.setFrame(frame, display: true)
        } else {
            let p = NotchPanel(frame: frame)
            let host = ClickThroughHostingView(rootView: NotchView(model: model))
            host.frame = NSRect(origin: .zero, size: frame.size)
            host.autoresizingMask = [.width, .height]
            p.contentView = host
            p.orderFrontRegardless()
            panel = p
        }
    }

    private func trackMouse() {
        guard let screen = targetScreen, let panel else { return }
        let p = NSEvent.mouseLocation
        let size = model.islandSize
        let island = CGRect(
            x: screen.frame.midX - size.width / 2,
            y: screen.frame.maxY - size.height,
            width: size.width,
            height: size.height
        )
        // A slightly forgiving target so it's easy to hit the notch from the menu bar
        let target = model.expanded ? island.insetBy(dx: -12, dy: -12) : island.insetBy(dx: -6, dy: -4)
        let inside = target.contains(p)

        if inside {
            leftSince = nil
            if hoverSince == nil { hoverSince = Date() }
            if !model.expanded, let since = hoverSince, Date().timeIntervalSince(since) > 0.12 {
                model.setExpanded(true)
            }
        } else {
            hoverSince = nil
            if model.expanded {
                if leftSince == nil { leftSince = Date() }
                if let since = leftSince, Date().timeIntervalSince(since) > 0.3 {
                    model.setExpanded(false)
                    leftSince = nil
                }
            }
        }
        // Only catch clicks while the pointer is over the island; everything else passes through.
        let catches = inside && (model.expanded || model.mode.isFlash)
        if panel.ignoresMouseEvents == catches { panel.ignoresMouseEvents = !catches }
    }

    // MARK: Menu bar item

    private func makeStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName: "capsule.fill", accessibilityDescription: "Horizon Notch")
        let menu = NSMenu()
        for minutes in [1, 5, 10, 25] {
            let m = NSMenuItem(title: "Start \(minutes)-minute timer", action: #selector(startTimer(_:)), keyEquivalent: "")
            m.tag = minutes
            m.target = self
            menu.addItem(m)
        }
        menu.addItem(.separator())
        let login = NSMenuItem(title: "Open at login", action: #selector(toggleLogin(_:)), keyEquivalent: "")
        login.target = self
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
        menu.addItem(.separator())
        let quit = NSMenuItem(title: "Quit Horizon Notch", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.addItem(quit)
        item.menu = menu
        statusItem = item
    }

    @objc private func startTimer(_ sender: NSMenuItem) {
        model.timers.start(minutes: Double(sender.tag))
    }

    @objc private func toggleLogin(_ sender: NSMenuItem) {
        do {
            if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            NSSound.beep()
        }
        sender.state = SMAppService.mainApp.status == .enabled ? .on : .off
    }
}
