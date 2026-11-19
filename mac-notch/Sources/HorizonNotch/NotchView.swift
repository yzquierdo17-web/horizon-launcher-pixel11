import AppKit
import SwiftUI

private let callGreen = Color(red: 0.20, green: 0.78, blue: 0.35)
private let timerOrange = Color(red: 1.0, green: 0.62, blue: 0.04)
private let endRed = Color(red: 1.0, green: 0.27, blue: 0.23)
private let dim = Color.white.opacity(0.6)

/// The black shape: square top that flares into the menu bar, rounded bottom.
struct NotchShape: Shape {
    var top: CGFloat
    var bottom: CGFloat

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(top, bottom) }
        set { top = newValue.first; bottom = newValue.second }
    }

    func path(in r: CGRect) -> Path {
        var p = Path()
        let b = min(bottom, r.height / 2, (r.width - top * 2) / 2)
        p.move(to: CGPoint(x: r.minX, y: r.minY))
        // Little outward curve at the top left, like the real notch
        p.addQuadCurve(to: CGPoint(x: r.minX + top, y: r.minY + top), control: CGPoint(x: r.minX + top, y: r.minY))
        p.addLine(to: CGPoint(x: r.minX + top, y: r.maxY - b))
        p.addQuadCurve(to: CGPoint(x: r.minX + top + b, y: r.maxY), control: CGPoint(x: r.minX + top, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX - top - b, y: r.maxY))
        p.addQuadCurve(to: CGPoint(x: r.maxX - top, y: r.maxY - b), control: CGPoint(x: r.maxX - top, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX - top, y: r.minY + top))
        p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.minY), control: CGPoint(x: r.maxX - top, y: r.minY))
        p.closeSubpath()
        return p
    }
}

struct NotchView: View {
    @ObservedObject var model: NotchModel

    var body: some View {
        let mode = model.mode
        let size = model.islandSize
        let flare: CGFloat = 8
        let big = mode == .expanded || mode.isFlash

        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                NotchShape(top: flare, bottom: big ? 26 : (model.hasNotch ? 10 : size.height / 2))
                    .fill(Color.black)
                    .frame(width: size.width + flare * 2, height: size.height)

                content(mode)
                    .frame(width: size.width, height: size.height, alignment: .top)
                    .clipped()
                    .transition(.opacity)
                    .id(modeKey(mode))
            }
            .shadow(color: .black.opacity(big ? 0.35 : 0), radius: 14, y: 6)
            .animation(.spring(response: 0.38, dampingFraction: 0.78), value: size)
            .animation(.easeOut(duration: 0.18), value: modeKey(mode))
            .onTapGesture { if mode.isFlash { model.dismissFlash() } }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private func modeKey(_ m: NotchMode) -> String {
        switch m {
        case .idle: return "idle"
        case .charging: return "charging"
        case .media: return "media"
        case .timer: return "timer"
        case .call: return "call"
        case .expanded: return "expanded"
        case .flash: return "flash"
        }
    }

    @ViewBuilder
    private func content(_ mode: NotchMode) -> some View {
        switch mode {
        case .idle:
            Color.clear
        case .charging:
            Wings(notchWidth: model.notch.width, height: model.notch.height) {
                Image(systemName: "bolt.fill").foregroundStyle(callGreen)
            } right: {
                Text("\(model.battery.level)%").font(.system(size: 12, weight: .semibold)).foregroundStyle(callGreen)
            }
        case .media:
            Wings(notchWidth: model.notch.width, height: model.notch.height) {
                Artwork(image: model.media.artwork, size: 20, corner: 5)
            } right: {
                Bars(playing: model.media.track?.playing == true, color: artTint(model.media.artwork))
            }
        case .timer:
            Wings(notchWidth: model.notch.width, height: model.notch.height) {
                Image(systemName: "timer").foregroundStyle(timerOrange)
            } right: {
                TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
                    Text(formatClock(model.timers.items.first.map { $0.end.timeIntervalSince(ctx.date) } ?? 0))
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundStyle(timerOrange)
                }
            }
        case .call:
            Wings(notchWidth: model.notch.width, height: model.notch.height) {
                Image(systemName: "mic.fill").foregroundStyle(timerOrange)
            } right: {
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    Text(formatClock(model.mic.since.map { ctx.date.timeIntervalSince($0) } ?? 0))
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .foregroundStyle(callGreen)
                }
            }
        case .flash(let f):
            FlashView(flash: f, model: model)
        case .expanded:
            ExpandedView(model: model)
        }
    }
}

/// Something on each side of the notch.
private struct Wings<L: View, R: View>: View {
    let notchWidth: CGFloat
    let height: CGFloat
    @ViewBuilder var left: L
    @ViewBuilder var right: R

    var body: some View {
        HStack(spacing: 0) {
            left.frame(maxWidth: .infinity, alignment: .center)
            Color.clear.frame(width: notchWidth)
            right.frame(maxWidth: .infinity, alignment: .center)
        }
        .font(.system(size: 13, weight: .semibold))
        .frame(height: height)
    }
}

private struct FlashView: View {
    let flash: Flash
    @ObservedObject var model: NotchModel

    var body: some View {
        VStack(spacing: 0) {
            Color.clear.frame(height: model.notch.height)
            HStack(spacing: 12) {
                switch flash {
                case .pluggedIn, .unplugged:
                    let on = flash == .pluggedIn
                    Image(systemName: on ? "battery.100.bolt" : "battery.75")
                        .font(.system(size: 20))
                        .foregroundStyle(on ? callGreen : .white)
                    Text(on ? "Charging" : "On battery").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                    Spacer()
                    Text("\(model.battery.level)%").font(.system(size: 18, weight: .semibold)).foregroundStyle(on ? callGreen : .white)
                case .timerDone(let label):
                    Image(systemName: "timer").font(.system(size: 20)).foregroundStyle(timerOrange)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Time's up").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                        Text(label).font(.system(size: 12)).foregroundStyle(dim)
                    }
                    Spacer()
                    Text("Click to dismiss").font(.system(size: 11)).foregroundStyle(dim)
                }
            }
            .padding(.horizontal, 22)
            .frame(height: 52)
        }
    }
}

private struct ExpandedView: View {
    @ObservedObject var model: NotchModel

    var body: some View {
        VStack(spacing: 0) {
            // Header row beside the notch
            HStack {
                Text(Date.now, format: .dateTime.weekday(.wide).month().day())
                    .font(.system(size: 11, weight: .medium)).foregroundStyle(dim)
                Spacer()
                if model.battery.hasBattery {
                    HStack(spacing: 4) {
                        Image(systemName: model.battery.pluggedIn ? "bolt.fill" : "battery.75")
                        Text("\(model.battery.level)%")
                    }
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(model.battery.pluggedIn ? callGreen : dim)
                }
            }
            .padding(.horizontal, 24)
            .frame(height: model.notch.height)

            HStack(alignment: .top, spacing: 18) {
                MediaPanel(media: model.media)
                    .frame(maxWidth: .infinity)
                Rectangle().fill(Color.white.opacity(0.1)).frame(width: 1, height: 120)
                VStack(alignment: .leading, spacing: 10) {
                    if model.mic.inUse {
                        HStack(spacing: 8) {
                            Circle().fill(timerOrange).frame(width: 8, height: 8)
                            Text("\(model.mic.appName) is using the mic")
                                .font(.system(size: 12, weight: .medium)).foregroundStyle(.white)
                                .lineLimit(1)
                        }
                    }
                    TimersPanel(timers: model.timers)
                }
                .frame(width: 190, alignment: .leading)
            }
            .padding(.horizontal, 22)
            .padding(.top, 8)
            Spacer(minLength: 0)
        }
    }
}

private struct MediaPanel: View {
    @ObservedObject var media: MediaMonitor

    var body: some View {
        if let t = media.track {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    Artwork(image: media.artwork, size: 56, corner: 12)
                        .onTapGesture { media.openApp() }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                        Text(t.artist).font(.system(size: 12)).foregroundStyle(dim).lineLimit(1)
                        Text(t.app.rawValue).font(.system(size: 10)).foregroundStyle(dim.opacity(0.7))
                    }
                    Spacer(minLength: 0)
                    Bars(playing: t.playing, color: artTint(media.artwork))
                }
                if t.duration > 0 {
                    TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
                        let pos = t.positionNow(ctx.date)
                        HStack(spacing: 8) {
                            Text(formatClock(pos)).font(.system(size: 10).monospacedDigit()).foregroundStyle(dim)
                            GeometryReader { g in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(Color.white.opacity(0.2))
                                    Capsule().fill(Color.white).frame(width: g.size.width * min(max(pos / t.duration, 0), 1))
                                }
                                .contentShape(Rectangle())
                                .onTapGesture(coordinateSpace: .local) { p in
                                    media.seek(to: Double(p.x / g.size.width) * t.duration)
                                }
                            }
                            .frame(height: 4)
                            Text("-" + formatClock(t.duration - pos)).font(.system(size: 10).monospacedDigit()).foregroundStyle(dim)
                        }
                    }
                }
                HStack(spacing: 28) {
                    ControlButton(symbol: "backward.fill", size: 16) { media.previous() }
                    ControlButton(symbol: t.playing ? "pause.fill" : "play.fill", size: 22) { media.playPause() }
                    ControlButton(symbol: "forward.fill", size: 16) { media.next() }
                }
                .frame(maxWidth: .infinity)
            }
        } else {
            VStack(spacing: 6) {
                Image(systemName: "music.note").font(.system(size: 22)).foregroundStyle(dim)
                Text("Nothing playing").font(.system(size: 12, weight: .medium)).foregroundStyle(dim)
                Text("Play something in Music or Spotify").font(.system(size: 10)).foregroundStyle(dim.opacity(0.7))
            }
            .frame(maxWidth: .infinity, minHeight: 110)
        }
    }
}

private struct TimersPanel: View {
    @ObservedObject var timers: TimerStore

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Timers").font(.system(size: 11, weight: .semibold)).foregroundStyle(dim)
            TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(timers.items.prefix(2)) { item in
                        HStack(spacing: 8) {
                            Text(formatClock(item.end.timeIntervalSince(ctx.date)))
                                .font(.system(size: 16, weight: .semibold).monospacedDigit())
                                .foregroundStyle(timerOrange)
                            Text(item.label).font(.system(size: 10)).foregroundStyle(dim).lineLimit(1)
                            Spacer(minLength: 0)
                            Button { timers.cancel(item.id) } label: {
                                Image(systemName: "xmark.circle.fill").foregroundStyle(dim)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            HStack(spacing: 6) {
                ForEach([1, 5, 10, 25], id: \.self) { m in
                    Button { timers.start(minutes: Double(m)) } label: {
                        Text("\(m)m")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 38, height: 24)
                            .background(Capsule().fill(Color.white.opacity(0.14)))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

private struct ControlButton: View {
    let symbol: String
    let size: CGFloat
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size))
                .foregroundStyle(.white)
                .frame(width: size + 18, height: size + 18)
                .background(Circle().fill(Color.white.opacity(hover ? 0.14 : 0)))
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

private struct Artwork: View {
    let image: NSImage?
    let size: CGFloat
    let corner: CGFloat

    var body: some View {
        Group {
            if let image {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                ZStack {
                    Color.white.opacity(0.15)
                    Image(systemName: "music.note").font(.system(size: size * 0.45)).foregroundStyle(.white)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
}

/// Little equaliser bars that dance while music plays.
private struct Bars: View {
    let playing: Bool
    let color: Color

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !playing)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            HStack(spacing: 2) {
                ForEach(0..<4, id: \.self) { i in
                    let speeds = [5.1, 6.7, 4.3, 5.9]
                    let h = playing ? 0.3 + 0.7 * abs(sin(t * speeds[i] + Double(i))) : 0.2
                    Capsule().fill(color).frame(width: 3, height: 14 * h)
                }
            }
            .frame(height: 14)
        }
    }
}

/// A bright colour from the album art.
private func artTint(_ image: NSImage?) -> Color {
    guard let image,
          let tiff = image.tiffRepresentation,
          let rep = NSBitmapImageRep(data: tiff),
          let small = rep.cgImage,
          let ctx = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                              space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    else { return .white }
    ctx.interpolationQuality = .medium
    ctx.draw(small, in: CGRect(x: 0, y: 0, width: 1, height: 1))
    guard let data = ctx.data else { return .white }
    let px = data.bindMemory(to: UInt8.self, capacity: 4)
    let c = NSColor(red: CGFloat(px[0]) / 255, green: CGFloat(px[1]) / 255, blue: CGFloat(px[2]) / 255, alpha: 1)
    var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    c.getHue(&h, saturation: &s, brightness: &b, alpha: &a)
    return Color(hue: Double(h), saturation: Double(min(max(s * 1.2, 0.35), 0.9)), brightness: 1)
}
