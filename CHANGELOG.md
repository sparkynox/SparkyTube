# SparkyTube Changelog

# v2.1

## New Features

- **Own update system**: updates come from the SparkyTube update server (and from the GitHub release if the server is down or still waking up). Settings > Update shows the latest version, the changelog and one Update button. It downloads the universal APK inside the app and opens the installer, no browser or release page needed
- **Update section in Settings**: System update, Version, Automatic update check, Update notifications and Messages in one place
- **Messages from the developer**: news and notes can be sent to every user from the admin panel. They show up as a notification and in Settings > Update > Messages
- **Private admin panel** (separate small server on Render, see `sparkytube-server`): publish a release (paste the APK links or import the latest GitHub release), send a message to every user, see and set the download counter. Data lives in a free Supabase database, so nothing is lost when the server restarts
- **Download counter on the website**: the site now shows how many people downloaded the app. The count is kept in the database so it never resets. A repeat download from the same address within an hour counts once
- **Website download buttons** go through the server so downloads are counted, with GitHub as the fallback if the server can't be reached
- ⋮ menu has an **Update** shortcut
- **Plugins (beta)**: install small JavaScript plugins from Settings > Plugins (paste code, a link or a file). They run inside the YouTube page with a small API (events, saved data, toast, current video, download) and only get the permissions they list. See `PLUGINS.md` and `plugins-examples/`
- ⋮ menu has a **Plugins** shortcut

## Improvements

- **New look for Settings, Update and Plugins**: flat grouped lists, one accent colour, no more glowing gradient cards
- **Faster resolve, real quality list**: with Data Saver off, NewPipe now runs next to yt-dlp and its list (up to 4K) is used whenever it has more than 360p, it gets up to 12 seconds before falling back to yt-dlp (yt-dlp's android clients only return the muxed 360p now). A 360p-only result is no longer cached, so the next play tries for the full list again. The same video is never resolved twice at once
- The "Before you message me" popup that appeared on every app open is gone
- The update popup on app open is replaced by a small bar at the bottom with an Update button, plus a notification

## Bug Fixes

- **Watch history records the whole video**: the hidden web player is no longer collapsed to 0px (YouTube paused it a few seconds in), it is kept playing muted and follows the native player's pause/play
- **PiP showed half the video**: the player now fills the whole PiP window and goes back to its normal size when you leave PiP
- **Data Saver off still gave 360p only**: yt-dlp had its own hidden Data Saver switch that was on by default. It now follows the main Data Saver Mode, and the extra switch is removed from Settings

## Notes

- Updates are checked in the background about every 6 hours (only with internet). Turn it off with Settings > Update > Automatic update check
- Android will ask once to allow SparkyTube to install apps, that's needed for in-app updates
- The update server is on a free plan and sleeps when nobody uses it, so the very first check after a while can take up to a minute
- The APK must be signed with the same key as the installed one, otherwise Android refuses the update

---

# v2.0 (v1.9 → v2.0)

## New Features

- **Settings home**: Settings now opens on a category list (Features, YouTube, Player and quality, Extraction, Downloads, Appearance, Experimental, App). Tap one to open its page, back goes to the list first. Search still looks through everything
- **HTML Settings only**: the Classic/Stylish switch is gone, the HTML settings screen is the only one now
- **Shortcuts grid**: the ⋮ menu is now a quick-access grid (Settings, Downloads, Offline, Appearance, Player, Extraction, YT Settings, Logs, Anime, About, Support) that jumps straight to the page. Toggles like Ad blocking are not in there on purpose
- **Download card under the player**: while a video plays, a small card shows which video is downloading right now with progress, size and speed, plus how many are active or queued. Tap it to open the download manager

## Improvements

- **Watch history**: the background HTML5 player no longer stops after 3 seconds, it keeps playing muted so YouTube counts a proper watch. When it finishes it just stops, it does not loop and it never autoplays to another video. Only ExoPlayer finishing moves on to the next video
- **Long videos start faster**: in yt-dlp Data Saver mode, videos of 15 minutes or more now use a small adaptive 360p stream instead of the muxed 360p file, whose big index made 30min to 2h videos take very long to show the first frame

## Bug Fixes

- Fixed a paused video resuming by itself after pressing Home or Recents
- Fixed pausing from the notification not sticking (the app was forcing playback back on)

## Known Issues

- The background HTML5 player is back to streaming for the whole video, so it uses extra data again (this is the price of proper watch history)
- Chapters only work for videos resolved through NewPipe, not the yt-dlp or server paths
- Audio-only download has no bitrate choice, you get a single audio option
- The Custom HTML feed puts every video in one "Recommended" shelf, section-wise grouping is still to come
- Long-press download currently works only in the Native (RecyclerView) feed
