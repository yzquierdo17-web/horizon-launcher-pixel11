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

### For work
- **Work and Personal tabs** in the drawer when your phone has a work profile, with a **Pause work apps** button that turns off all work apps and their notifications. Paused apps show in grey; tap one to turn work apps back on.
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
