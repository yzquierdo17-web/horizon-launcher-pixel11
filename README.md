# Horizon Launcher

A clean, minimal home screen for the Google Pixel 11 Pro XL, built with Kotlin and Jetpack Compose.

## Features
- Big clock and date (tap the clock for alarms, the date for your calendar)
- Dock of up to 5 apps with a frosted background
- Swipe up, or tap **Search apps**, to open the app drawer
- Instant search: type, then press Go to open the first match
- Home screen pages: swipe left and right; hold a dragged item at the edge to change page
- Drag and drop: long-press any app, folder or widget and drag it anywhere, into the dock, or onto **Remove**
- Folders: drop one app onto another; tap to open, tap the name to rename
- Widgets: long-press the home screen → **Widgets**; long-press a widget to resize or remove it
- Long-press an app in the drawer to add it to the home screen or dock, see App info, or uninstall
- Long-press the home screen for widgets, wallpaper and default home app settings
- Colors follow your wallpaper (Material You)
- Pull down at the top of the drawer, or press Back, to close it

### Fast and polished
- **Optimized build:** the install link now gives an R8-optimized release build with a startup profile. It launches faster and scrolls more smoothly than the old debug build.
- **Icon cache:** finished icons (shape, pack, themed colours) are saved, so Home appears instantly after a restart or update.
- **Frosted glass dock and search bar** that float above the wallpaper.
- **Themes** (Settings → Look → Theme):
  - **Wallpaper:** Material You colours from your wallpaper.
  - **Graphite:** soft neutral greys.
  - **Midnight:** deep navy with blue accents.
  - **Forest:** dark green.
  - **Plum:** dark purple.
  - **AMOLED:** true black, which saves battery on OLED screens.
  - **Paper:** a light drawer, folders and settings. Home keeps white text on your wallpaper, and the lock-screen dial stays dark.

  The drawer, folders, settings, add wheel and lock-screen dial all follow the theme.
- **Motion:** the wallpaper glides as you swipe pages (with wide or live wallpapers; you can turn this off), pages settle with a soft spring, and apps ripple in when the drawer opens.
- **Inter font** across Horizon (clock, labels, settings, dial and lock screen). Switch back to the phone's font in Settings → Look → Font. Inter is © The Inter Project Authors, SIL Open Font License 1.1 (see `FONT-LICENSE-Inter.txt`).

### Everyday
- **Notification dots** on app icons with unread notifications, or the number if you prefer (Settings → Look). It uses the same notification access as the Dynamic Island, and it respects apps' own "no dots" settings and skips ongoing ones like music and calls.
- **Double-tap to lock:** double-tap empty space on Home to turn the screen off. It uses the island's Accessibility helper, so turn Horizon on in Settings → Accessibility once.
- **App shortcuts** at the top of an icon's long-press menu (Gmail → Compose, Maps → Navigate home…), like Pixel. Android only gives these to the default Home app.

### Updates that keep your layout
Builds are signed with a permanent key once two repository secrets are set (Settings → Secrets and variables → Actions):
- `HORIZON_KEYSTORE`: the base64 keystore
- `HORIZON_KEY_PASSWORD`: its password

After that, every new APK installs over the last one. Without them, builds fall back to a temporary key, and you may need to uninstall first.

### Today, stacks and updates
- **Today page:** swipe right on your first Home page. It shows a greeting, today's remaining agenda (with Join buttons for calls starting soon), the weather now plus the next six hours and today's high and low, your battery (with "full in…" while charging), and which apps have unread notifications. Swipe left or press Back to return.
- **Discover:** a news feed like the Pixel's. Big picture cards with the source, how long ago, a heart, share and a ⋮ menu (Not interested, Don't show this source, Manage interests). Stories come from Google News for your country plus the publishers' own feeds (NYT, BBC, NPR, The Verge, Ars Technica, ESPN and more), keep loading as you scroll, open in an in-app browser tab, and refresh when you pull down. Hearting a story shows you more from that source. Pick topics or add any RSS feed in Settings → News.
- **Flight radar:** live planes around you on a radar that sweeps, with planes gliding between 5-second updates, pointing where they're heading and coloured by altitude. Tap a plane for its flight number, airline, route (JFK → LHR), altitude, speed, heading, aircraft type and distance from you, or open it on the live map. Pick 10, 25, 50 or 100 miles (or km). The Today page shows a "Flights overhead" card with a mini radar; you can also search "radar" in the app drawer. Data comes from the free adsb.lol and airplanes.live community networks.
- **At a Glance:** under the clock, the one or two things that matter right now: a running timer, a flight you follow, your team's live score, rain on the way, charging time left, tonight's alarm.
- **Now Playing:** play, pause, skip and a progress bar on the Today page for whatever's playing.
- **Gestures:** choose what swipe down, double-tap, pinch and two-finger swipes do (notifications, quick settings, lock, flashlight, camera, Today, flight radar, any app and more).
- **Per-app tweaks:** long-press an app → Customize to rename it, give it your own picture as its icon, or hide its name.
- **Smart drawer:** group chips (Social, Media, Games, Productivity, Travel, News, Shopping) and suggestions that follow your day.
- **Search more:** calendar events, photos/videos/music, web suggestions and Play Store, as well as apps, contacts and settings.
- **Scores & stocks:** follow teams (NFL, NBA, MLB, NHL, soccer, college) with live scores, and a stock watchlist with today's chart.
- **Followed topics & Saved:** follow any keyword in Discover; save stories for later.
- **Flight alerts:** a notification when a plane flies low over you, and follow any flight number until it lands.
- **Backup:** to a file or Google Drive (custom icons included), plus your phone's automatic Google backup.
- **Widget stacks:** long-press a widget and choose **Add widget to stack**, then pick another widget. Swipe up and down on the stack to switch between them; dots on the side show where you are. Long-press to remove the one showing, or the whole stack.
- **Updates inside Horizon:** Horizon checks your GitHub releases every few hours. When a newer build exists, a chip appears under the clock ("Horizon update ready · Install"), and Settings → Updates shows what's new. One tap downloads it and Android asks you to confirm; your layout is kept. The first time, Android asks you to allow Horizon to install apps.

### Clock faces
Settings → Look → **Clock face** opens a picker with live previews:
- **Large** and **Compact**
- **Dial**, **Dial · Minimal** (no ticks), **Dial · Bold** (heavy hour, solid accent pill) and **Dial · Outline** (outlined hour)
- **Stacked:** bold hours over thin minutes
- **Words:** "It's four forty-five"

The clock sits on the first Home page only and slides away with it as you swipe. The lock-screen dial uses the same dial variant. **Label size** (small, medium or large) is in the same section.

### Dial clock
- **Settings → Look → Clock → Dial:** a watch-bezel clock. The big hour sits on the left, and a minute scale and a seconds scale turn smoothly so the current minute and second line up in a pill, with the date in small spaced capitals beside it. Tap the dial for Clock, or the date for Calendar. It redraws about 30 times a second and stops when Home isn't on screen.
- **On your lock screen:** Settings → Look → **Dial on lock screen** opens the *Horizon Dial* live wallpaper. Tap **Set wallpaper** and choose **Lock screen**. It shows the same dial with smooth seconds on a dark charcoal background, plus your battery level, and only animates while the screen is on. Pixel's own lock-screen clock always stays on top; to make it less noticeable, pick a small clock style in Wallpaper & style → Lock screen.

### Only the dial on the lock screen
Android doesn't let apps switch off the Pixel's own lock screen clock, so **Settings → Only the dial on the lock screen** makes Horizon's dial the lock screen instead: it covers Pixel's clock, and you swipe up to unlock (then fingerprint, face or PIN as usual). Pull down from the top for notifications and quick settings. It needs **Show over all apps** (Dynamic Island, step 1) to be on. The always-on display still shows Pixel's clock.

### Dynamic Island
A black pill that wraps the camera hole and grows to show what's happening, over every app and on the lock screen, like on iPhone.
- **Calls:** a green pill with the call length. Incoming calls open the island with **Answer** and **Decline**; tap during a call for **Hang up**.
- **Timers and stopwatches** from the Clock app count down in orange (paused ones show the time left).
- **Music:** album art on one side and moving bars in the art's colour on the other. Tap or pull down for the track, a seek bar and play/pause/skip; hold to open the music app.
- **Charging:** plugging in or unplugging shows the battery level for a moment; while charging it stays as a small green pill.
- **New notifications** drop down from the camera for a few seconds. Tap to open, swipe up to dismiss.
- Tap or pull down to expand, tap anywhere else or swipe up to shrink, hold to open the app behind it. It springs and settles like the iPhone's.
- **Move and resize** (Settings → Dynamic Island): drag the island anywhere on the screen, or back to the camera to snap it on. Make it smaller or bigger.

**Setup** (Settings → Dynamic Island):
1. **Show over all apps** turns on *Horizon Dynamic Island* in Accessibility. Android only lets accessibility services draw above the status bar; Horizon's only draws the island and doesn't read your screen.
2. **Notification access** lets it see calls, timers, music and notifications.

Because Horizon is installed from a download, Android may grey these out as restricted. Open Settings → Apps → Horizon, tap **⋮ → Allow restricted settings**, then try again.

### Add wheel and icons
- **Add wheel:** long-press empty space on Home. Spin the dial with your finger (it coasts and snaps, with a tick per item), then tap the middle: add a widget, add an app, change icon style or wallpaper, or open settings. You can also tap an item to spin it to the top.
- **Icon style** (also in Settings → Look): reshape every icon to Circle, Squircle, Rounded square or Teardrop so the grid lines up, or keep the phone default. You can also use any icon pack made for Nova, Lawnchair or ADW. Icons a pack doesn't cover are restyled with its plate and mask, and work-profile badges are kept.

### For work
- **Work and Personal tabs** in the drawer. Put any app on Work: long-press it and choose **Add to Work tab**, or open the Work tab and tap **Choose apps** to tick several at once. If your phone has a work profile, its apps are on Work automatically, and a **Pause work apps** button turns them and their notifications off. Paused apps show in grey; tap one to turn work apps back on.
- **Work hours** (Settings → Work): choose start and end times and days. During work hours the drawer opens on Work and the clock shows "Work hours · until 6:00 PM". Optionally pause work apps after hours and turn them back on in the morning. Android only allows this while Horizon is on screen, so if the phone is locked at closing time it happens the next time you go Home.
- **Next meeting under the clock** from any calendar on the phone (Google, Outlook and others). It skips all-day events and declined invites. A **Join** button appears 15 minutes before the start when the event has a Meet, Zoom, Teams, Webex, Whereby, GoTo or Chime link. Tap the meeting itself to open it in Calendar. Horizon asks for calendar access once.
- **Suggested apps:** your five most-opened apps at the top of the drawer. Counts stay on the phone and can be turned off.
- **Smarter search:** matches the start of any word, initials ("gm" finds Google Maps) and ignores accents.
- **A–Z scroller** on the drawer's edge, with a letter bubble and a haptic tick.

## Getting the app
On your phone, open this link to download the newest version, then tap the file to install it:

**https://github.com/yzquierdo17-web/horizon-launcher-pixel11/releases/latest/download/Horizon.apk**

Every push to `main` builds a new APK and publishes it there automatically. The first time, Android will ask you to allow installs from your browser.

Then press Home and choose **Horizon**, or go to Settings → Apps → Default apps → Home app.

If Android says the app conflicts with an existing Horizon, uninstall the old one first. Each build is signed with a temporary key, and Android won't update an app signed with a different key.
