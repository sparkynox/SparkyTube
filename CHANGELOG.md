# SparkyTube Changelog (v1.9 → v2.0)

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
