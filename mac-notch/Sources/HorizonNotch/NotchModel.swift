import AppKit
import Combine
import SwiftUI

/// What the island shows right now.
enum NotchMode: Equatable {
    case idle
    case charging            // small green pill while plugged in
    case media
    case timer
    case call                // microphone in use (FaceTime, Zoom, Teams…)
    case expanded
    case flash(Flash)        // a moment: plugged in, unplugged, timer done

    var isFlash: Bool { if case .flash = self { return true } else { return false } }
}

enum Flash: Equatable {
    case pluggedIn
    case unplugged
    case timerDone(String)
}

final class NotchModel: ObservableObject {
    let media = MediaMonitor()
    let battery = BatteryMonitor()
    let mic = MicMonitor()
    let timers = TimerStore()

    @Published private(set) var expanded = false
    @Published private(set) var flash: Flash?
    /// Real notch size on this Mac (or a stand-in on screens without one).
    @Published private(set) var notch = CGSize(width: 185, height: 32)
    @Published private(set) var hasNotch = false

    private var bag = Set<AnyCancellable>()
    private var flashWork: DispatchWorkItem?

    func start() {
        // Re-publish whenever any source changes, so the view and hover tracking stay in step
        [media.objectWillChange, battery.objectWillChange, mic.objectWillChange, timers.objectWillChange].forEach {
            $0.receive(on: RunLoop.main).sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &bag)
        }
        battery.onPlugChange = { [weak self] plugged in
            self?.show(plugged ? .pluggedIn : .unplugged, for: 3)
        }
        timers.onFinish = { [weak self] item in
            self?.show(.timerDone(item.label), for: 8)
            Sound.chime()
        }
        media.start()
        battery.start()
        mic.start()
        timers.startTicking()
    }

    func measure(screen: NSScreen) {
        let top = screen.safeAreaInsets.top
        if top > 0, let left = screen.auxiliaryTopLeftArea?.width, let right = screen.auxiliaryTopRightArea?.width {
            notch = CGSize(width: screen.frame.width - left - right, height: top)
            hasNotch = true
        } else {
            // No notch: float a pill at the top centre, the height of the menu bar
            let bar = screen.frame.maxY - screen.visibleFrame.maxY
            notch = CGSize(width: 180, height: max(bar, 24))
            hasNotch = false
        }
    }

    func setExpanded(_ value: Bool) {
        guard value != expanded else { return }
        if value, flash != nil { flash = nil }
        withAnimation(.spring(response: 0.38, dampingFraction: 0.8)) { expanded = value }
    }

    private func show(_ f: Flash, for seconds: Double) {
        flashWork?.cancel()
        withAnimation(.spring(response: 0.38, dampingFraction: 0.75)) { flash = f }
        let work = DispatchWorkItem { [weak self] in
            withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { self?.flash = nil }
        }
        flashWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    func dismissFlash() {
        flashWork?.cancel()
        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { flash = nil }
    }

    var mode: NotchMode {
        if let flash { return .flash(flash) }
        if expanded { return .expanded }
        if mic.inUse { return .call }
        if !timers.items.isEmpty { return .timer }
        if media.track != nil { return .media }
        if battery.hasBattery && battery.pluggedIn { return .charging }
        return .idle
    }

    /// Size of the black shape for each mode. Hover tracking uses this too.
    var islandSize: CGSize {
        let wing: CGFloat = 46
        switch mode {
        case .idle: return notch
        case .charging, .media, .timer, .call:
            return CGSize(width: notch.width + wing * 2, height: notch.height)
        case .flash: return CGSize(width: max(notch.width + 180, 380), height: notch.height + 52)
        case .expanded: return CGSize(width: max(notch.width + 340, 540), height: notch.height + 158)
        }
    }
}

enum Sound {
    static func chime() {
        let s = NSSound(named: "Glass")
        s?.play()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { NSSound(named: "Glass")?.play() }
    }
}
