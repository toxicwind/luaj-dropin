# LuaJDropIn API

## Globals

| Global | Type | Description |
|---|---|---|
| `context` | `android.content.Context` | Application context (pre-bound) |
| `files` | `java.io.File` | `context.filesDir` |
| `cache` | `java.io.File` | `context.cacheDir` |
| `log(...)` | function | Appends args to response output + Logcat |
| `exec(cmd)` | function | Runs `/system/bin/sh -c`, returns exit code, captures stdout+stderr |
| `bind(name)` | function | Classloader-aware `Class.forName` — resolves AndroidX + app classes from Lua |
| `new(clazz)` | function | Constructs a class instance (handles inner classes like `Data$Builder`) |

## Classloader-aware bridge (`bind` / `new`)

Plain `luajava.bindClass` uses the DEFAULT classloader, which on an Android
daemon thread is the system loader — app/AndroidX classes fail with
`ClassNotFoundException`. `bind(name)` resolves through the app's own
classloader so EVERY class is reachable:

```lua
local WorkManager = bind("androidx.work.WorkManager")
local wm = WorkManager:getInstance(context)
wm:enqueueUniqueWork("catalog-sync-source-1", bind("androidx.work.ExistingWorkPolicy").REPLACE, request)
```

`new(clazz)` constructs instances reliably, including inner classes:

```lua
local DataBuilder = bind("androidx.work.Data$Builder")
local d = new(DataBuilder)          -- no-arg constructor
d:putLong("sourceId", 1)
local payload = d:build()
```

## HTTP Endpoints

### GET /

Status probe. Returns:

```json
{"ok":true,"pkg":"tv.own.owntv","pid":12345,"uid":1000}
```

### POST /run

Body: `{"script": "<lua>"}` (or raw Lua with `Content-Type: text/plain`).

Returns:

```json
{"ok":true,"output":"<console output>\n"}
```

or on error:

```json
{"ok":false,"output":"","error":"<exception message>"}
```

## Kotlin Integration

```kotlin
LuaJDropIn.start(this)                          // default port 38383
LuaJDropIn.start(this, 40000)                   // custom port
LuaJDropIn.configureGlobals { globals ->
    globals.set("myApi", CoerceJavaToLua.coerce(MyApi()))
}
```

## Auto-deploy watcher + state restore

`bin/watch.ts` closes the edit→deploy loop:

- **Lua lane** — any `.lua` in `lua/` POSTs to `/run` on save (250ms debounce). `_`-prefixed files are skipped.
- **Source lane** — any `.kt/.java/.xml/.kts` change triggers `gradle assembleStandardDebug` → `adb push` → `pm install -r` → relaunch (1.5s debounce).
- **State restore** — a rebuild kills the Lua runtime state (globals, DB handles, listeners). After every relaunch the watcher polls the lane, then runs `lua/init.lua` FIRST (idempotent: dedupe profiles, insert missing sources, re-link), then the remaining `.lua` files in order.

```bash
bun bin/watch.ts                # both lanes
bun bin/watch.ts --no-source    # Lua only
bun bin/watch.ts --no-lua       # source only
bun bin/watch.ts --lua-dir lua --host http://127.0.0.1:38383
```

## Port Fallback

If `:38383` is taken, the server tries `:38384` … `:38392` (10 attempts).

## Security Notes

- **No auth** — bind to a trusted network (adb reverse/forward, LAN) only.
- The shell escape hatch `exec` runs as the app UID.
- Do not ship this in production builds.
