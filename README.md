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

### Dial clock
- **Settings → Look → Clock → Dial:** a watch-bezel clock. The big hour sits on the left, and a minute scale and a seconds scale turn smoothly so the current minute and second line up in a pill, with the date in small spaced capitals beside it. Tap the dial for Clock, or the date for Calendar. It redraws about 30 times a second and stops when Home isn't on screen.
- **On your lock screen:** Settings → Look → **Dial on lock screen** opens the *Horizon Dial* live wallpaper. Tap **Set wallpaper** and choose **Lock screen**. It shows the same dial with smooth seconds on a dark charcoal background, plus your battery level, and only animates while the screen is on. Pixel's own lock-screen clock always stays on top; to make it less noticeable, pick a small clock style in Wallpaper & style → Lock screen.

### Dynamic Island
A black pill that wraps the camera hole at the top of Home and grows to show what's happening. Turn it on or off in **Settings → Dynamic Island**.
- **Calls:** a green pill with the call length. Incoming calls open the island with **Answer** and **Decline**; tap during a call for **Hang up**.
- **Timers and stopwatches** from the Clock app count down in orange.
- **Music:** album art on one side and moving bars in the art's colour on the other. Tap for the track, a seek bar and play/pause/skip; hold to open the music app.
- **Charging:** plugging in or unplugging shows the battery level for a moment; while charging it stays as a small green pill.
- **New notifications** drop down from the camera for a few seconds. Tap one to open it, or swipe up to dismiss.
- Tap the island to expand it, tap outside or swipe up to shrink it, hold to open the app behind it.
- Calls, timers, music and notifications need **Notification access** (Settings → Dynamic Island → Notification access). Because Horizon is installed from a download, Android may say the setting is restricted: open Settings → Apps → Horizon, tap **⋮ → Allow restricted settings**, then try again.
- A launcher can only draw on its own screen, so the island shows on Home and not inside other apps.

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
