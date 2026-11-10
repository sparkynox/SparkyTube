# SparkyTube Changelog

# v2.5

## New Features

- **Glassy mini-player**: the mini player is now a rounded glass bar docked above the bottom bar, with the video, title, play/pause and close. Tap it or swipe it up to open the full player
- **Sleep timer**: menu (⋮) > Sleep timer. Slide to pick 5 to 120 minutes and press Start, or choose end of the video. It shows the time left and can be turned off from the same sheet
- **Mix panel on normal videos**: when YouTube has a Mix for the video you are watching, the Mix list now shows under the player, just like on the YouTube site. Tap an item to carry on inside that Mix
- **Swipe to mini player**: on the normal watch screen, drag the video down and the whole page fades with it. Let go and the video shrinks and flies into the mini player; the bar fades in. Opening it again rises the player back up. Short drags spring back
- **New loading indicator**: every spinner and pull-to-refresh now uses the Material 3 Expressive loading indicator, the morphing shape from the Play Store
- **Stylish icons**: settings, the ⋮ menu and the accounts sheet use cookie-shaped icon tiles
- **More animation**: cards fade and rise in, the watch screen and search slide in, the bottom bar icons pop when selected, Like and Subscribe animate between their states
- **Real glass mini player**: the mini bar now has a blurred copy of the video's thumbnail behind it. The blur is done natively (C++, `lunoblur.cpp`)
- **Player settings menu**: the gear on the player now opens a YouTube-style menu with Quality, Playback speed, Loop video, Music mode, Sleep timer, Audio track and More (Download, Downloaded videos, Audio FX). Sleep timer and Audio FX moved here from the ⋮ menu
- **Luno Glass** (Settings > Appearance): frosted glass sheets with real window blur behind them, a blurred copy of the video behind the watch page, glass panels and bottom bar. Needs Android 12+ with window blur turned on. On phones that can't do it the switch refuses to turn on and says why
- **Multiple accounts**: tap your picture on Home to open the Accounts sheet. Add as many YouTube accounts as you want, tap one to switch (Home, Subscriptions, Library and history follow the account you pick), or remove one with the X. Add account opens Google's own account chooser, so an account already on the phone is one tap, and "Use another account" adds a new one. Backing out keeps your current account

## Fixes

- Videos you already liked now show as Liked, and channels you already subscribed to show as Subscribed on the watch page

# v2.4

## New Features

- **Channel pages**: tapping a channel now opens a native page instead of the website. Banner, subscribers, description, Subscribe, and tabs for Videos, Shorts, Live, Playlists and About. Pull to refresh, loads more as you scroll
- **Lyrics in Music Mode**: synced lyrics over the music picture, with a glow on the line being sung, blurred lines further away and a background in the cover's colours. Word by word when the source has per-word timing, line by line otherwise. In fullscreen / landscape the cover sits on the left and the lyrics on the right. Settings > App > Lyrics in Music Mode (the title and channel name are sent to lrclib.net and lyrics-api.boidu.dev to find the song)
- **Ambient glow**: a soft glow in the video's colours behind the watch page. Settings > App > Ambient glow
- **Stats**: ⋮ menu > Stats. Watch time, music mode time, videos watched, the last 7 days, top channels and most watched. Everything stays on the phone, with a Reset button
- **Queue**: Clear all, move items up and down, and the queue is kept after you close the app. Playlists and mixes can be queued too
- **Notification Next / Previous** now follow your queue, then the playlist, then related. Previous restarts the video, or goes back to the one before it
- The Video Quality shortcut in the top bar is an HD badge now, so it is no longer a second gear

## Fixes

- Pressing Enter in search could still show the old suggestions instead of the results
- A video could be skipped about a second after it started, because an empty player reported "ended"
- A playlist or mix now carries on after a queued video, instead of jumping to Up next
- A saved resume spot too close to the end no longer ends the video right away
- Player errors and queue decisions now show in Settings > Logs (tags Player and Queue)

# v2.3

## New Features

- **Native plugins**: install `.dex` / `.jar` Kotlin plugins from Settings > Plugins. They get full access: the Activity, WebView and player objects, JS eval, saved data, events. The app asks before installing one. See `PLUGINS.md`
- **Audio FX (LunoFX)**: a native C++ audio engine inside the player. 10-band equalizer, bass boost, stereo width, reverb, **8D audio** (sound circles around your head), a **volume normalizer** that evens out loud and quiet videos, and a limiter so nothing clips. Presets: Bass Boost, Lofi Chill, Slowed + Reverb, Nightcore, 8D Audio, Vocal Boost, Loud + Even. Settings > App > Audio FX, or the ⋮ menu
- **Anime upscaler**: scales low-resolution anime up (1.5x or 2x, up to 1080p) and runs every frame through a GPU pass that keeps line art thin and crisp. Off / Light / Strong in Settings > App > Anime upscaler
- **HTTP/3 (Cronet)**: video loads through Chromium's network stack with QUIC when Google Play services has it, OkHttp otherwise. Toggle in Settings
- **Native ad filter engine**: EasyList / EasyPrivacy / Anti-Adblock *path and wildcard* rules (not just domains) matched in C++, about 15 microseconds per request
- **Native crash reports**: C++ crashes (the kind the Kotlin handler never saw) now show up in "App crashed last time"
- **Remote config**: admin switches for features and bans, signed with ed25519 and verified in C++. See `REMOTE_CONFIG.md`
- **Artwork popups**: new artwork for No Internet, crash, plugin error, VPN and more. New VPN warning (once per run, can be turned off from the popup)
- **Plugin UI text**: `setUiText` lets a plugin replace app text, first key is the Home greeting. Example plugin: Welcome Admin
- **Bigger ad and tracker block lists**: ~52,000 domains from EasyList, EasyPrivacy and the Anti-Adblock Warning Removal list (one hash lookup per request). Plugins that talk to those domains are refused at install (JS and native)
- **Media cache**: 256 MB disk cache for video, so replays and rewinds don't hit the network again

## Bug Fixes

- The old round spinner no longer shows on top of the new loader while a video is loading
- Volume and brightness swipes now work only in fullscreen, so scrolling or swiping on the normal player no longer changes them
- Music mode in the mini player: the picture is now the small rounded thumbnail on the left instead of covering the whole bar
- Save to playlist and Save to Watch later now really save (the request was cancelled when the menu closed). It also waits for YouTube's answer before showing Added
- Videos with a Mix under them now carry on inside the Mix when they end, instead of jumping to Up next
- **Background history**: watch history sometimes wasn't saved while the app was in the background. Android could kill the hidden player's page process and nothing brought it back. The renderer is now kept at high priority, rebuilt if it dies anyway, and the hidden watch page is retried (up to 3 times) when it doesn't load or has no video
- **Autoplay / queue after a video ends**: the app used to jump back to search (or wherever you opened the video from) instead of playing the next one. It now plays your queue first, then the playlist, then the first related video. Works in the mini-player, PiP and audio mode too, and a background video change no longer pops the watch page open

## Improvements

- **Long videos start much faster**: googlevideo files are now requested in small range pieces instead of one open-ended request, which YouTube throttles
- Downloads: files are split into small segments that a few workers take one by one (no more waiting for the slowest piece), the space is reserved up front, and a retry resumes where it stopped
- Player uses OkHttp (HTTP/2, kept-alive connections), 20s back buffer
- Next video's first chunk is prefetched while the current one ends, sized to your measured speed
- Small C++ library (`libsparkynet`) measures network speed and warms DNS for the stream host

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
