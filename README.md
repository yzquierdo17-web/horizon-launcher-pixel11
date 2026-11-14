# Slate Launcher

A clean, Pixel-style Android launcher built with Kotlin and Jetpack Compose, made for large Pixel phones like the Pixel 11 Pro XL.

## New in v0.3: built for work

- **Work and Personal tabs** in the drawer, when your phone has a work profile. A **Pause work apps** button turns off all work apps and their notifications in one tap. Paused apps show in grey, and tapping one offers to turn work apps back on.
- **Work hours** (Settings → Work): pick start and end times and which days. During work hours:
  - Home shows its own **work row of apps**. Add or remove apps while in work hours and it remembers them separately from your personal row.
  - The drawer opens on the **Work** tab.
  - A "Work hours · until 6:00 PM" chip shows under the clock.
  - **Pause work apps after hours** can turn work apps off at the end of the day and back on in the morning. Android only allows this while Slate is open on screen, so if the phone is locked at 6:00 PM it happens the next time you go Home.
- **Next meeting under the clock** (asks for calendar access once). It shows the meeting happening now or the next one in the coming 12 hours, from any calendar on the phone (Google, Outlook/Exchange and others). All-day events and invites you declined are skipped.
  - A **Join** button appears 15 minutes before the start when the event's location or notes contain a Meet, Zoom, Teams, Webex, Whereby, GoTo or Chime link. Tap the meeting itself to open it in Calendar.
- **Home screen widgets.** Long-press empty space → **Widgets** lists every widget on the phone, with previews and grouped by app. Android asks once to let Slate add widgets, then shows the widget's own setup screen if it has one.
  - Long-press a widget to make it Small, Medium, Large or Extra large, move it up or down, or remove it.
  - Widgets report their real size, so Android 12+ widgets switch to the right layout.

## New in v0.2

- **Blurred wallpaper** behind a see-through drawer, using Android 12+ cross-window blur. The blur follows your finger and switches off on its own when Battery Saver disables blur.
- **Suggested apps** at the top of the drawer, picked from what you open most. Launch counts are stored only on the phone.
- **A–Z fast scroller** on the drawer edge, with a letter bubble and a haptic tick on each letter.
- **Next alarm** (within 24 hours) shown next to the date. Tap it to open Clock.
- **Haptic click** when the drawer opens.

## What's in v0.1

- **Home screen** with a big date + clock that you can tap to open Calendar or Clock, a row of favourite apps, a 5-icon dock and a search pill at the bottom.
- **Material You everywhere.** The colours come from your wallpaper, and the Home text and status-bar icons switch between light and dark to stay readable on any wallpaper.
- **Gestures:** swipe up to open the app drawer, which follows your finger and settles with a spring. Swipe down on Home to open notifications. Pull the drawer down from the top of the list to close it. Press Home to go straight back.
- **App drawer** in A–Z order with search: start of a name, start of any word, initials ("gm" finds Google Maps) and accent-insensitive matching. Press Enter to open the top result. There are also "Search the web" and "Search Play Store" options.
- **Long-press menu** with the app's shortcuts (once Slate is your default Home app), App info, Add to / Remove from Home, Pin to Dock, Hide and Uninstall.
- **Themed icons** (Android 13+ monochrome icons tinted with your colours) that you can turn on or off.
- **Work profile** apps appear with their badge.
- **Settings:** grid columns (4–6), icon size (80–125%), labels on/off, hidden apps, Wallpaper & style, and a "Set as default" button.
- Icons shrink with a springy animation when pressed, you get haptic feedback, and apps open with a scale-up animation from their icon.

## Build & install

1. Install the latest **Android Studio**.
2. **File → Open** and pick the `SlateLauncher` folder. Let Gradle sync. Android Studio downloads Gradle and the SDK, and may offer to upgrade AGP or Kotlin. Accepting is fine.
3. On the phone, turn on **Developer options → USB debugging**, connect it, then press **Run**.
4. Press the Home button and pick **Slate → Always**, or go to Slate's settings and tap **Set as default**.

Shortcuts in the long-press menu only show up once Slate is the default Home app. This is an Android rule.

## Project layout

```
app/src/main/java/com/slate/launcher/
  MainActivity.kt          Home activity, edge-to-edge, handles the Home button
  LauncherViewModel.kt     Combines installed apps with saved layout/settings
  data/AppRepository.kt    LauncherApps: apps, icons, shortcuts, launching, live updates
  data/LauncherPrefs.kt    DataStore: dock, home, hidden apps, grid, icon size
  ui/LauncherRoot.kt       Puts Home, drawer, settings, back and gestures together
  ui/HomeScreen.kt         Clock, favourites, dock, search pill
  ui/AppDrawer.kt          Search + grid + ranked search
  ui/AppIcon.kt            Icon, label, press animation, long-press menu
  ui/DrawerState.kt        Drawer physics, drag gesture, pull-to-close
  ui/SettingsScreen.kt     Settings
  util/SystemActions.kt    Notification shade, wallpaper, role request, wallpaper text-colour hint
```

## Platform limits for third-party launchers

- **Recents / Overview** and the swipe-up-to-home animation stay with the Pixel Launcher. Android doesn't let other launchers replace them.
- The **Google Discover feed** (swipe right) is only available to Google's own launcher.
- The **notification-shade swipe** relies on a long-standing system hook that other launchers also use. If a future Android release blocks it, the swipe just does nothing.

## Ideas for next versions

1. Drag-and-drop to rearrange icons and widgets, and drag from the drawer onto Home
2. Folders
3. Multiple Home pages
4. Weather in the date and clock area
5. Icon packs and icon-shape choices
6. Double-tap to lock (needs an accessibility service)
7. Backup and restore of your layout
