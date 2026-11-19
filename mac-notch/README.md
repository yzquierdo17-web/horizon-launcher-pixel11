# Horizon Notch (Mac)

A Dynamic Island for your MacBook's notch. It sits over the notch, grows when something's happening, and opens into a panel when you point at it.

- **Music:** album art and moving bars beside the notch while Music or Spotify plays. Point at the notch for the track, a seek bar and play/pause/skip; click the art to open the app.
- **Charging:** a moment's banner when you plug in or unplug, and a small green pill with the battery level while charging.
- **Timers:** start 1, 5, 10 or 25-minute timers from the panel or the menu-bar icon. The countdown shows beside the notch, and a chime and banner tell you when time's up.
- **Calls:** when an app is using your microphone (FaceTime, Zoom, Teams, Slack, Discord…) the island shows an orange mic and how long it's been on.
- On Macs without a notch it floats as a pill at the top of the screen.

macOS doesn't let apps read other apps' notifications, so there are no notification banners on the Mac.

## Install
Download **HorizonNotch-mac.zip** from the newest *Horizon Notch* release, unzip it, and drag **Horizon Notch** to Applications. The first time, right-click it and choose **Open** (it isn't notarised). Allow it to control Music and Spotify when macOS asks. Use the capsule icon in the menu bar to turn on **Open at login** or quit.

To build it yourself instead (needs Xcode or `xcode-select --install`): run `./build_app.sh` in this folder.
