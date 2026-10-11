# SparkyTube plugins (Beta)

Plugins are in beta. The API below can still change between versions.

A plugin is plain JavaScript that runs inside the YouTube page. Install it from
**Settings > Plugins** (or the ⋮ menu): paste the code, give a https link, or pick a file.
After installing or toggling one, the page reloads by itself (not while a video is playing).

Plugins live in `plugins-examples/`, copy one and change it.

## File format

One `.js` file with a header, then your code. `sparky` is already there.

```js
// ==SparkyPlugin==
// @id          my-plugin          (3-40 chars: a-z 0-9 . _ -, must be unique)
// @name        My plugin
// @version     1.0
// @author      you
// @description What it does
// @permissions toast, video       (optional, see below)
// ==/SparkyPlugin==

sparky.toast('hello');
```

Or a `.zip` with `plugin.json` (`id`, `name`, `version`, `author`, `description`, `permissions`),
`main.js` and an optional `style.css` (injected as is).

## Permissions

Saving data (`sparky.storage`) is always allowed. The rest has to be listed in `@permissions`:

| permission | lets the plugin |
|---|---|
| `toast` | show a small message |
| `video` | read the current video with `sparky.video.info()` |
| `download` | open the download picker for the current video |
| `open_url` | open a https link in the browser |

The Plugins screen shows these as chips on every plugin.

## API

```js
sparky.on(event, fn)          // returns a function that removes the listener
sparky.ready(fn)              // runs when the page is ready
sparky.addStyle(css)          // adds a <style>, returns a function that removes it
sparky.storage.get(key, def)  // saved values are per plugin, any JSON value
sparky.storage.set(key, value)
sparky.storage.remove(key)
sparky.toast(text)            // needs toast
sparky.video.id()             // id of the video in the address bar
sparky.video.info()           // needs video: {videoId, title, position, duration, playing, native}
sparky.video.download()       // needs download
sparky.openUrl(url)           // needs open_url
sparky.log(...)               // goes to Settings > Logs
sparky.meta                   // {name, version, permissions}
```

## Events

| event | data | when |
|---|---|---|
| `navigate` | `{url}` | the page changed (YouTube never fully reloads) |
| `videochange` | `{videoId}` | the video in the address bar changed |
| `videostart` | `{videoId, title}` | the native player started a video |
| `play` / `pause` | | the native player started or stopped playing |
| `videoend` | | the native player reached the end |

An error inside your code or a handler is caught and written to the logs, it can't break the page
or other plugins.

## Good to know

- Plugins only run on youtube.com pages.
- Each plugin has its own private token, a page script can't call the app on its behalf.
- Values saved with `sparky.storage` are removed with the plugin.

## Later

Plugins are JS/CSS only for now. Planned for a later version:

- Kotlin/dex style plugins, for things JS can't reach (native screens, the player, settings pages)
- Image changing support (swap the app's images/icons from a plugin)
- A plugin store, so plugins can be browsed and installed from the app
