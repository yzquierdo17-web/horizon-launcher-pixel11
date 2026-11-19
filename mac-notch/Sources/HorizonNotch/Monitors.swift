import AppKit
import CoreAudio
import Foundation
import IOKit.ps

// MARK: - Music (Apple Music and Spotify, through AppleScript)

/// macOS no longer lets other apps read the system "Now Playing" info, so Horizon Notch asks
/// Music and Spotify directly. macOS asks once for permission to control each app.
final class MediaMonitor: ObservableObject {
    struct Track: Equatable {
        var app: Player
        var title: String
        var artist: String
        var album: String
        var playing: Bool
        var position: Double
        var duration: Double
        var artworkURL: String
        var readAt: Date

        func positionNow(_ now: Date = Date()) -> Double {
            guard playing else { return position }
            return min(position + now.timeIntervalSince(readAt), duration > 0 ? duration : .infinity)
        }
    }

    enum Player: String {
        case music = "Music", spotify = "Spotify"
        var bundleID: String { self == .music ? "com.apple.Music" : "com.spotify.client" }
    }

    @Published private(set) var track: Track?
    @Published private(set) var artwork: NSImage?

    // NSAppleScript is only safe on the main thread; each call takes a few milliseconds.
    private let queue = DispatchQueue.main
    private var timer: Timer?
    private var artKey = ""

    func start() {
        poll()
        timer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] _ in self?.poll() }
    }

    private func isRunning(_ p: Player) -> Bool {
        !NSRunningApplication.runningApplications(withBundleIdentifier: p.bundleID).isEmpty
    }

    private func poll() {
        // Only talk to apps that are already open, so polling never launches them
        let players = [Player.spotify, .music].filter(isRunning)
        queue.async { [weak self] in
            guard let self else { return }
            let found = players.compactMap { self.read($0) }
            let pick = found.first { $0.playing } ?? found.first
            DispatchQueue.main.async {
                if pick != self.track { self.track = pick }
                self.loadArtwork(for: pick)
            }
        }
    }

    private func read(_ p: Player) -> Track? {
        let sep = "|||"
        let source: String
        switch p {
        case .spotify:
            source = """
            tell application "Spotify"
                if player state is stopped then return "stopped"
                set t to current track
                return (player state as string) & "\(sep)" & (name of t) & "\(sep)" & (artist of t) & "\(sep)" & (album of t) & "\(sep)" & (player position as string) & "\(sep)" & ((duration of t) / 1000 as string) & "\(sep)" & (artwork url of t)
            end tell
            """
        case .music:
            source = """
            tell application "Music"
                if player state is stopped then return "stopped"
                set t to current track
                return (player state as string) & "\(sep)" & (name of t) & "\(sep)" & (artist of t) & "\(sep)" & (album of t) & "\(sep)" & (player position as string) & "\(sep)" & (duration of t as string) & "\(sep)" & ""
            end tell
            """
        }
        guard let out = Script.run(source), out != "stopped" else { return nil }
        let f = out.components(separatedBy: sep)
        guard f.count >= 7 else { return nil }
        func num(_ s: String) -> Double { Double(s.replacingOccurrences(of: ",", with: ".")) ?? 0 }
        return Track(
            app: p, title: f[1], artist: f[2], album: f[3],
            playing: f[0] == "playing",
            position: num(f[4]), duration: num(f[5]),
            artworkURL: f[6], readAt: Date()
        )
    }

    private func loadArtwork(for t: Track?) {
        guard let t else { artwork = nil; artKey = ""; return }
        let key = "\(t.app.rawValue)|\(t.title)|\(t.album)"
        guard key != artKey else { return }
        artKey = key
        artwork = nil
        switch t.app {
        case .spotify:
            guard let url = URL(string: t.artworkURL) else { return }
            URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
                guard let data, let img = NSImage(data: data) else { return }
                DispatchQueue.main.async { if self?.artKey == key { self?.artwork = img } }
            }.resume()
        case .music:
            queue.async { [weak self] in
                let d = Script.runData("tell application \"Music\" to get data of artwork 1 of current track")
                let img = d.flatMap { NSImage(data: $0) }
                DispatchQueue.main.async { if self?.artKey == key { self?.artwork = img } }
            }
        }
    }

    // Controls

    func playPause() { command("playpause") }
    func next() { command("next track") }
    func previous() { command("previous track") }

    func seek(to seconds: Double) {
        command("set player position to \(Int(seconds))")
    }

    func openApp() {
        guard let t = track,
              let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: t.app.bundleID) else { return }
        NSWorkspace.shared.openApplication(at: url, configuration: .init())
    }

    private func command(_ c: String) {
        guard let app = track?.app else { return }
        queue.async { [weak self] in
            _ = Script.run("tell application \"\(app.rawValue)\" to \(c)")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { self?.poll() }
        }
    }
}

enum Script {
    static func run(_ source: String) -> String? {
        var error: NSDictionary?
        let result = NSAppleScript(source: source)?.executeAndReturnError(&error)
        if error != nil { return nil }
        return result?.stringValue
    }

    static func runData(_ source: String) -> Data? {
        var error: NSDictionary?
        let result = NSAppleScript(source: source)?.executeAndReturnError(&error)
        if error != nil { return nil }
        return result?.data
    }
}

// MARK: - Battery

final class BatteryMonitor: ObservableObject {
    @Published private(set) var level = 100
    @Published private(set) var charging = false
    @Published private(set) var pluggedIn = false
    @Published private(set) var hasBattery = false

    var onPlugChange: ((Bool) -> Void)?
    private var source: CFRunLoopSource?
    private var first = true

    func start() {
        read()
        let ctx = Unmanaged.passUnretained(self).toOpaque()
        source = IOPSNotificationCreateRunLoopSource({ ctx in
            guard let ctx else { return }
            let me = Unmanaged<BatteryMonitor>.fromOpaque(ctx).takeUnretainedValue()
            DispatchQueue.main.async { me.read() }
        }, ctx)?.takeRetainedValue()
        if let source { CFRunLoopAddSource(CFRunLoopGetMain(), source, .defaultMode) }
    }

    private func read() {
        guard let blob = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(blob)?.takeRetainedValue() as? [CFTypeRef] else { return }
        for ps in list {
            guard let d = IOPSGetPowerSourceDescription(blob, ps)?.takeUnretainedValue() as? [String: Any],
                  (d[kIOPSTypeKey] as? String) == kIOPSInternalBatteryType else { continue }
            let cur = d[kIOPSCurrentCapacityKey] as? Int ?? 0
            let full = Swift.max(d[kIOPSMaxCapacityKey] as? Int ?? 100, 1)
            let plugged = (d[kIOPSPowerSourceStateKey] as? String) == kIOPSACPowerValue
            hasBattery = true
            level = cur * 100 / full
            charging = d[kIOPSIsChargingKey] as? Bool ?? false
            if plugged != pluggedIn {
                pluggedIn = plugged
                if !first { onPlugChange?(plugged) }
            }
        }
        first = false
    }
}

// MARK: - Microphone in use (calls)

/// Apple doesn't let apps see other apps' calls or notifications, so the island treats
/// "the microphone is in use" as a call and names the call app it finds running.
final class MicMonitor: ObservableObject {
    @Published private(set) var inUse = false
    @Published private(set) var since: Date?
    @Published private(set) var appName = ""

    private var timer: Timer?
    private static let callApps: [(String, String)] = [
        ("com.apple.FaceTime", "FaceTime"),
        ("us.zoom.xos", "Zoom"),
        ("com.microsoft.teams2", "Teams"),
        ("com.microsoft.teams", "Teams"),
        ("com.tinyspeck.slackmacgap", "Slack"),
        ("com.hnc.Discord", "Discord"),
        ("com.cisco.webexmeetingsapp", "Webex"),
        ("net.whatsapp.WhatsApp", "WhatsApp"),
    ]

    func start() {
        check()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.check() }
    }

    private func check() {
        let on = Self.defaultInput().map(Self.isRunning) ?? false
        if on != inUse {
            inUse = on
            since = on ? Date() : nil
            appName = on ? Self.callAppName() : ""
        }
    }

    private static func callAppName() -> String {
        for (id, name) in callApps where !NSRunningApplication.runningApplications(withBundleIdentifier: id).isEmpty {
            return name
        }
        return "Mic"
    }

    private static func defaultInput() -> AudioDeviceID? {
        var id = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultInputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &id)
        return status == noErr && id != 0 ? id : nil
    }

    private static func isRunning(_ id: AudioDeviceID) -> Bool {
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        return AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &value) == noErr && value != 0
    }
}

// MARK: - Timers

final class TimerStore: ObservableObject {
    struct Item: Identifiable, Equatable {
        let id = UUID()
        let label: String
        let end: Date
        let total: TimeInterval
    }

    @Published private(set) var items: [Item] = []
    var onFinish: ((Item) -> Void)?
    private var tick: Timer?

    func start(minutes: Double) {
        let total = minutes * 60
        let label = minutes < 1 ? "\(Int(total)) sec timer" : "\(Int(minutes)) min timer"
        items.append(Item(label: label, end: Date().addingTimeInterval(total), total: total))
        items.sort { $0.end < $1.end }
    }

    func cancel(_ id: UUID) { items.removeAll { $0.id == id } }

    func startTicking() {
        tick = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else { return }
            let now = Date()
            let done = self.items.filter { $0.end <= now }
            guard !done.isEmpty else { return }
            self.items.removeAll { $0.end <= now }
            done.forEach { self.onFinish?($0) }
        }
    }
}

func formatClock(_ seconds: Double) -> String {
    let s = max(0, Int(seconds.rounded(.up)))
    let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
}
