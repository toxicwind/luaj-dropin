# LuaJDropIn

<div align="center">

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.0.0-green.svg?style=for-the-badge)](CHANGELOG.md)
[![Platform](https://img.shields.io/badge/platform-Android%207%2B-orange.svg?style=for-the-badge)](docs/)
[![Lua](https://img.shields.io/badge/LuaJ-3.0.1-purple.svg?style=for-the-badge)](docs/API.md)

</div>

## Drop-in LuaJ remote control plane & hot-reload server for any Android APK

**LuaJDropIn** embeds a zero-dependency HTTP + LuaJ runtime into any Android app. One line of Kotlin, one port, full remote control: run Lua scripts over HTTP against live app state — SQLite, SharedPreferences, classloaders, and any Java/Kotlin API via `luajava`.

<p align="center">
  ![LuaJDropIn demo](docs/demo.svg)
</p>

## Quickstart

### 1. Add the dependency

```kotlin
// app/build.gradle.kts
implementation("org.luaj:luaj-jse:3.0.1")
```

### 2. Copy one file

Drop `src/net/luaj/dropin/LuaJDropIn.kt` into your module.

### 3. Start it in Application.onCreate

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LuaJDropIn.start(this)          // binds :38383 (auto-increments if busy)
    }
}
```

### 4. Control it over HTTP

```bash
# Status probe
curl http://localhost:38383/

# Run any Lua script against the live app
curl -X POST http://localhost:38383/run \
  -H 'Content-Type: application/json' \
  -d '{"script": "log(context.packageName)"}'

# → {"ok":true,"output":"tv.own.owntv\n"}
```

## Features

- **Zero dependencies** — plain `java.net.ServerSocket`, no web framework, no reflection hacks.
- **Full LuaJ JSE platform** — `luajava.bindClass`, `CoerceJavaToLua`, coroutines, io, os.
- **Live app context** — `context`, `files`, `cache` globals pre-bound to the running app.
- **Shell escape hatch** — `exec(cmd)` for anything Lua can't reach.
- **Console capture** — `log(...)` streams back to the HTTP response.
- **Port auto-fallback** — binds `:38383`, walks up if taken.
- **Custom globals** — `configureGlobals { ... }` to inject your own APIs.

## API

| Endpoint | Method | Body | Response |
|---|---|---|---|
| `/` | GET | — | `{"ok":true,"pkg":"...","pid":123,"uid":1000}` |
| `/status` | GET | — | same as `/` |
| `/run` | POST | `{"script":"..."}` or raw Lua | `{"ok":true,"output":"..."}` / `{"ok":false,"error":"..."}` |

Pre-bound globals: `context`, `files`, `cache`, `log(...)`, `exec(cmd)`.

## Examples

See [examples/](examples/) for runnable scripts:

- [status.lua](examples/status.lua) — probe the running app
- [sqlite-inject.lua](examples/sqlite-inject.lua) — inject rows into a live Room/SQLite DB
- [dump-dex.lua](examples/dump-dex.lua) — trigger a BlackDex dump from Lua
- [prefs-lua.lua](examples/prefs-lua.lua) — read/write SharedPreferences

## Roadmap

- [x] HTTP server + Lua execution (`/status`, `/run`)
- [x] Pre-bound app context + shell escape hatch
- [ ] WebSocket streaming console
- [ ] Hot-reload file watcher (push `.lua` → auto-run)
- [ ] Auth token support

## Contributing

Contributions are what make the open-source community such an amazing place — read [CONTRIBUTING.md](CONTRIBUTING.md) and jump in.

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for more information.

## Contact & Acknowledgments

Maintainer: @toxicwind — open a [discussion](https://github.com/toxicwind/android-fleet/discussions).

---

> Don't forget to give the project a star! ⭐ Thanks again!
