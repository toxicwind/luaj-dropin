<div align="center">

# 🚀 LuaJDropIn

**One line of Kotlin. Full remote control of any Android app.**
**Three self-contained files. Drop-in. No rebuild. No root. No Frida.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.1.0-green.svg?style=for-the-badge)](CHANGELOG.md)
[![Platform](https://img.shields.io/badge/platform-Android%207%2B-orange.svg?style=for-the-badge)](docs/)
[![Tests](https://img.shields.io/badge/tests-9%2F9-brightgreen.svg?style=for-the-badge)](test/JavaBridgeTest.kt)
[![Lua](https://img.shields.io/badge/LuaJ-3.0.1-purple.svg?style=for-the-badge)](docs/API.md)
[![Protobuf](https://img.shields.io/badge/protobuf-pure--Lua-red.svg?style=for-the-badge)](lua/protobuf.lua)

**Run Lua against a live Android app — over HTTP. No rebuild. No root. No Frida.**
**Drop 3 self-contained files into any Kotlin project and call `LuaJDropIn.start(this)`.**

<p align="center">
  <img src="docs/demo.png" alt="LuaJDropIn demo — live session against an Android app" width="860">
</p>

</div>

---

## Why?

You're debugging an app on a device. You need to:

- 🗄️ **Inspect or mutate SQLite** — without pulling the DB
- 🔑 **Read SharedPreferences** — live, while the app is running
- 🧪 **Call any Java/Kotlin API** — WorkManager, Room, your own classes
- ⚡ **Hot-reload logic** — edit a `.lua` file, it's running on-device in 250ms
- 🛠️ **Shell out** — as the app's own UID
- 🧬 **Encode/decode protobuf** — pure-Lua wire format, no C, no luajava

Normally that's an APK rebuild, an Frida script, or a rooted shell. **LuaJDropIn is 3 self-contained files, one line of Kotlin, and a `curl`.**

## Quickstart (60 seconds) — drop into any Kotlin project

### 1. Add the LuaJ dependency

```kotlin
// app/build.gradle.kts
dependencies {
    // debug-only — never ship the control plane in release
    debugImplementation("org.luaj:luaj-jse:3.0.1")
}
```

> **Release safety:** `LuaJDropIn.start()` is a **no-op in release builds** —
> it checks `ApplicationInfo.FLAG_DEBUGGABLE` at runtime and returns early if
> the app isn't debuggable. So even if you copy the files into `src/main`,
> the control plane never binds in a release APK. (The `debugImplementation`
> above additionally keeps LuaJ itself out of the release binary.)

### 2. Copy the drop-in (3 files, one package)

Copy these three files into your app's source tree — nothing else. They are
self-contained (no transitive deps beyond LuaJ):

```
src/net/luaj/dropin/LuaJDropIn.kt   ← the HTTP server + Lua runner + start()
src/net/luaj/dropin/JavaBridge.kt   ← classloader-aware bind()/new() bridge
src/net/luaj/dropin/LuaJPatch.kt    ← force-hotpatch shim (fixes stock LuaJ)
```

Or, if you're on a monorepo, add this repo as a git submodule / module:

```bash
git submodule add https://github.com/toxicwind/luaj-dropin modules/luaj-dropin
```

### 3. Start it — one line

```kotlin
import net.luaj.dropin.LuaJDropIn

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LuaJDropIn.start(this)          // ← that's the whole integration
        // optional: LuaJDropIn.start(this, port = 38383)
    }
}
```

`start()` is an `object` method — call it from any `Application.onCreate()`.
It binds `:38383` (walks up if taken), pre-binds the app `context`, `files`,
`cache`, and the `log`/`exec`/`bind`/`new` globals, and serves the HTTP API.

### 4. Control it from your machine

```bash
# Is it alive?
curl http://localhost:38383/
# → {"ok":true,"pkg":"com.example.app","pid":1234,"uid":10000}

# Run Lua against the live app
curl -X POST http://localhost:38383/run \
  -H 'Content-Type: application/json' \
  -d '{"script": "log(context.packageName)"}'
# → {"ok":true,"output":"com.example.app\n"}
```

### 5. Go deeper — SQLite, live

```lua
-- read the app's own database
local SQLiteDatabase = luajava.bindClass("android.database.sqlite.SQLiteDatabase")
local db = SQLiteDatabase:openDatabase(context:getDatabasePath("app.db"):getPath(), nil, 0)
local c = db:rawQuery("SELECT COUNT(*) FROM users", nil)
c:moveToFirst()
log("users: " .. c:getLong(0))
db:close()
```

### 6. Lock it down (adb reverse only)

Don't open `:38383` to your LAN. Use `adb reverse` so only your dev machine
can reach it:

```bash
adb reverse tcp:38383 tcp:38383
curl http://localhost:38383/status   # now reachable only via adb
```

---

## What you can do

| Task | How |
|---|---|
| **Inspect SQLite** | `luajava` + `SQLiteDatabase` — read/write any table |
| **Read/Write prefs** | `context:getSharedPreferences(...)` |
| **Call app APIs** | `bind("com.your.app.SomeClass")` — every class reachable |
| **Trigger WorkManager** | `bind("androidx.work.WorkManager"):getInstance(context)` |
| **Shell out** | `exec("ls /data/data/" .. context.packageName)` |
| **Protobuf wire format** | `require("protobuf")` — encode/decode varint/fixed/len/nested/map |
| **Hot-reload** | `bun bin/watch.ts` — edit `.lua`, deployed on save |
| **After rebuild** | `lua/init.lua` runs automatically — state restored |

### The protobuf codec (`lua/protobuf.lua`)

A pure-Lua protobuf wire-format encoder/decoder — no C, no luajava, runs anywhere LuaJ does. Full wire support: varint, fixed32/fixed64, length-delimited, nested messages, repeated fields, and maps (as repeated map-entry messages).

```lua
local proto = require("protobuf")
local bytes = proto.encode({ 1 = "hello", 2 = { fixed32 = 42 } })
local msg = proto.decode(bytes)
log(msg[1])  -- "hello"
```

## Production deployment — the OwnTV fork control plane

LuaJDropIn is not just a library — it's **the live control plane for the OwnTV Android TV IPTV fork** (`tv.own.owntv` on the author's TV + Pixel). It's the orthogonal correction to a testability void: the fork's upstream shipped 0 testTag, 0 `testTagsAsResourceId`, and 20 Espresso androidTest fossils that never migrated to Compose. LuaJDropIn bypasses the Gradle build loop entirely — push a Lua script, see the result in 250ms.

### The semantic bridge

The fork's Compose UI was a **semantic black box** to uiautomator — the rail had `focusGroup()`, zero `testTag`, and icon-only glyphs with no `contentDescription`. LuaJDropIn's `bind()` + `new()` made the app's own classes reachable from Lua, enabling:

- `runSearch` bridge — drive the AIOStreams search lane (title → per-title stream fan-out → dedupe → Hindi/dub filter → selector sheet) headlessly
- `enqueueSync` bridge — fire `CatalogSyncWorker` for any source by ID
- `datastoreEdit` — singleton-safe synchronous DataStore mutation

### The force-sync lane (`lua/force-sync.lua`)

Discovers all sources from the live SQLite DB and enqueues their `CatalogSyncWorker`s — no hardcoded source IDs. Uses the TDD-proven `new()` exact-ctor (bypasses LuaJ's Overload which picks the no-arg ctor over the Class ctor):

```bash
curl -X POST http://localhost:38383/run \
  -H 'Content-Type: application/json' \
  --data-binary @lua/force-sync.lua
# → {"ok":true,"sources":5}
```

### Real-world numbers (2026-11-11)

- Full catalog sync (New8K source, 22,217 streams): **16.3s** via LuaJ-enqueued workers (was 25–55s/chunk on the old path)
- Search "ruin" → 21 results (Movies 16, Live TV 2, Series 3) with per-title stream fan-out
- UI automation: **35+ clickable nodes** visible to uiautomator after the semantic bridge

## Why it works where others don't

- **Zero dependencies** — plain `java.net.ServerSocket`. No web framework, no reflection hacks, no native code.
- **Classloader-aware bridge** — `bind("androidx.work.WorkManager")` resolves through the app's own classloader. Plain `luajava.bindClass` fails on Android daemon threads; this doesn't.
- **Force-hotpatch shim** — LuaJ ships an unreachable `getConstructor()` helper (proven by bytecode RE). `LuaJPatch` makes it work at bind time. No fork, no jar surgery.
- **Port auto-fallback** — `:38383` taken? Walks up until it finds a free port.
- **Debug-only by design** — never ship it. See the disclaimer below.

## Auto-deploy watcher

```bash
bun bin/watch.ts                # watch lua/ + src/
bun bin/watch.ts --no-source    # Lua only
```

- **Lua lane** — every `.lua` in `lua/` deploys on save (250ms debounce)
- **Source lane** — any Kotlin change → `gradle assemble` → `adb push` → `pm install -r` → relaunch
- **State restore** — a rebuild kills the Lua runtime. `lua/init.lua` runs first after every relaunch, rebuilding your state idempotently

## API

| Endpoint | Method | Body | Response |
|---|---|---|---|
| `/` | GET | — | `{"ok":true,"pkg":"...","pid":123,"uid":1000}` |
| `/status` | GET | — | same as `/` |
| `/run` | POST | `{"script":"..."}` or raw Lua | `{"ok":true,"output":"..."}` / `{"ok":false,"error":"..."}` |

Pre-bound globals: `context`, `files`, `cache`, `log(...)`, `exec(cmd)`, `bind(name)`, `new(clazz)`.

Full reference: [docs/API.md](docs/API.md) · [docs/LuaJPatch.md](docs/LuaJPatch.md)

## Examples

- [status.lua](examples/status.lua) — probe the running app
- [sqlite-inject.lua](examples/sqlite-inject.lua) — inject rows into a live Room/SQLite DB
- [dump-dex.lua](examples/dump-dex.lua) — trigger a BlackDex dump from Lua
- [prefs-lua.lua](examples/prefs-lua.lua) — read/write SharedPreferences
- [force-sync.lua](lua/force-sync.lua) — enqueue all CatalogSyncWorkers from live DB discovery
- [protobuf.lua](lua/protobuf.lua) — pure-Lua protobuf wire-format codec

## Roadmap

- [x] HTTP server + Lua execution (`/status`, `/run`)
- [x] Pre-bound app context + shell escape hatch
- [x] Classloader-aware `bind()` + `new()`
- [x] Force-hotpatch shim (`LuaJPatch`)
- [x] Auto-deploy watcher with state restore
- [x] Pure-Lua protobuf codec
- [x] Force-sync lane (DB-discovered WorkManager enqueue)
- [x] Compose semantic bridge (runSearch / enqueueSync / datastoreEdit)
- [ ] WebSocket streaming console
- [ ] Auth token support

## ⚠️ Disclaimer (read before use)

**This tool is a remote code execution plane.** The `/run` endpoint executes
arbitrary Lua with full access to the app's process: every Java/Kotlin class,
the SQLite database, SharedPreferences, the filesystem, and `exec()` as the
app UID. Anyone with network reach to the bound port can:

- read or modify the app's data (credentials, tokens, databases)
- execute shell commands as the app's user
- trigger any app API (playback, recording, purchases, account changes)

**By using this project you acknowledge:**

1. **Debug-only.** It is designed exclusively for development, testing, and
   reverse-engineering of YOUR OWN applications and devices. It must never be
   shipped in a production build, published to a store, or left running on a
   device you do not own.
2. **No warranty.** The software is provided "AS IS", without warranty of any
   kind. The author is not liable for any damage, data loss, or misuse.
3. **Your responsibility.** You are solely responsible for how this tool is
   used and for securing the endpoint (firewall, adb reverse only, no public
   exposure). Exposing `:38383` to an untrusted network is equivalent to
   publishing a root shell on that port.
4. **No circumvention endorsement.** Nothing here is intended to bypass DRM,
   licensing, or access controls on content or services you do not own. Use
   against your own hardware and accounts only.

This disclaimer mirrors the practice of similar tooling (Shizuku/rish, Frida,
Objection): the capability is neutral, the deployment is yours.

## Contributing

Contributions are what make the open-source community such an amazing place — read [CONTRIBUTING.md](CONTRIBUTING.md) and jump in.

## License

Distributed under the MIT License. See [LICENSE](LICENSE) for more information.

## Contact & Acknowledgments

Maintainer: @toxicwind — open a [discussion](https://github.com/toxicwind/luaj-dropin/discussions) or [file an issue](https://github.com/toxicwind/luaj-dropin/issues).

**Credits:**

- **[LuaJ](https://github.com/luaj/luaj)** (MIT) — the pure-Java Lua 5.2 VM + `luajava` bridge this project rides on. No fork: the force-hotpatch shim works around a genuine gap in the stock library (see [docs/LuaJPatch.md](docs/LuaJPatch.md)).
- **[Fengari](https://github.com/fengari-lua/fengari)** (MIT) — the JS Lua VM whose protocol insights informed the drop-in's design.
- **Shizuku / rish, Frida, Objection** — the tooling whose disclaimer practice this project mirrors.
- **OwnTV fork** (`ahXN00/OwnTV`, GPLv3) — the production deployment target whose testability void this project corrects.

---

> Don't forget to give the project a star! ⭐ Thanks again!