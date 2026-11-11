# LuaJDropIn API

## Globals

| Global | Type | Description |
|---|---|---|
| `context` | `android.content.Context` | Application context (pre-bound) |
| `files` | `java.io.File` | `context.filesDir` |
| `cache` | `java.io.File` | `context.cacheDir` |
| `log(...)` | function | Appends args to response output + Logcat |
| `exec(cmd)` | function | Runs `/system/bin/sh -c`, returns exit code, captures stdout+stderr |

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

## Port Fallback

If `:38383` is taken, the server tries `:38384` … `:38392` (10 attempts).

## Security Notes

- **No auth** — bind to a trusted network (adb reverse/forward, LAN) only.
- The shell escape hatch `exec` runs as the app UID.
- Do not ship this in production builds.
