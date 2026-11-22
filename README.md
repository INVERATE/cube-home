# Cube Home

A small, ad-free home screen for the Fire TV Cube (Fire OS 7), inspired by
[Plume Launcher](https://github.com/aradhyas8/Plume_Launcher). Plain Java on the Android SDK,
no libraries, no Gradle; the APK is about 60 KB.

- Hourly wallpaper (Amazon aerial frames, NASA Earth photos, Bing) with its description
- Clock, apps row (hold OK to move an app), All apps (hold OK for options)
- Jellyfin rows: Continue watching, Next up, Recently added; plays in
  [Wholphin](https://github.com/damontecres/Wholphin) (falls back to the Jellyfin app)
- Screensaver playing Fire TV's aerial videos, no ads
- Settings on the TV: Jellyfin server, rows, wallpaper, size, corners, font, colour, animation speed
- Self-update from this repository's releases

Fire OS keeps the Home button for Amazon's launcher; Cube Home's accessibility service
covers Amazon's home with Cube Home the moment it opens.

## Build and install

Needs the Android SDK (build-tools 34, platform 30) and a JDK under `~/android-dev` (see `build.sh`).

    ./build.sh                 # build/cubehome.apk, signed with ~/android-dev/cubehome.keystore
    ./install.sh 192.168.1.62  # install over ADB, grant permissions, enable the Home button service
    ./uninstall.sh 192.168.1.62

## Releases

`VERSION` holds one number. `./release.sh` builds it, tags `v<VERSION>` and uploads the APK to a
GitHub release; the TV finds it within a day (or Settings > Check for updates). Releases must be
signed with the same key as the installed app.

## Credits

Icons: [Lucide](https://lucide.dev) (ISC). Amazon aerial scene list and descriptions from
[Aerial Views](https://github.com/theothernt/AerialViews) (GPL-3.0); the videos stream from Amazon.
