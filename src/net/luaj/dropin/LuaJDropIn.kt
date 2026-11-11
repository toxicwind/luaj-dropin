package net.luaj.dropin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Drop-in LuaJ remote control plane & hot-reload server.
 * Embeddable in any Android app via a single line:
 *    LuaJDropIn.start(context)
 */
object LuaJDropIn {
    private const val TAG = "LuaJDropIn"
    var defaultPort = 38383

    @Volatile private var serverSocket: ServerSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var customGlobalsHandler: ((Globals) -> Unit)? = null

    fun configureGlobals(handler: (Globals) -> Unit) {
        customGlobalsHandler = handler
    }

    fun start(context: Context, port: Int = defaultPort) {
        if (serverSocket != null) return
        this.appContext = context.applicationContext
        this.defaultPort = port

        thread(isDaemon = true, name = "luaj-dropin-server") {
            var p = port
            while (p < port + 10) {
                try {
                    val server = ServerSocket(p)
                    serverSocket = server
                    Log.i(TAG, "LuaJ Drop-In server active on port :${p}")
                    while (!server.isClosed) {
                        val client = server.accept()
                        thread(isDaemon = true) { handleClient(client) }
                    }
                    break
                } catch (e: Throwable) {
                    Log.w(TAG, "Port ${p} unavailable: ${e.message}")
                    p++
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val path = parts[1]

                var contentLength = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val (code, contentType, resp) = when {
                    path == "/" || path == "/status" -> {
                        val status = JSONObject().apply {
                            put("ok", true)
                            put("pkg", appContext?.packageName ?: "unknown")
                            put("pid", android.os.Process.myPid())
                            put("uid", android.os.Process.myUid())
                        }
                        Triple(200, "application/json", status.toString())
                    }
                    path == "/run" -> {
                        val script = try {
                            JSONObject(body).optString("script", body)
                        } catch (_: Throwable) { body }

                        val (ok, output, err) = runLua(script)
                        val out = JSONObject().put("ok", ok).put("output", output).put("error", err ?: JSONObject.NULL).toString()
                        Triple(200, "application/json", out)
                    }
                    else -> Triple(404, "text/plain", "not found")
                }

                val out = s.getOutputStream()
                val respBytes = resp.toByteArray(Charsets.UTF_8)
                val headerText = "HTTP/1.1 $code OK\r\nContent-Type: $contentType\r\nContent-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n"
                out.write(headerText.toByteArray(Charsets.UTF_8))
                out.write(respBytes)
                out.flush()
            }
        } catch (_: Throwable) {}
    }

    data class LuaResult(val ok: Boolean, val output: String, val error: String?)

    fun runLua(script: String): LuaResult {
        val outBuf = StringBuilder()
        return try {
            val globals = JsePlatform.standardGlobals()
            val ctx = appContext

            if (ctx != null) {
                globals.set("context", CoerceJavaToLua.coerce(ctx))
                globals.set("files", CoerceJavaToLua.coerce(ctx.filesDir))
                globals.set("cache", CoerceJavaToLua.coerce(ctx.cacheDir))
            }

            // ── Classloader-aware Java bridge (mirrors AppLuaRunner) ──
            // luajava.bindClass uses the DEFAULT classloader, which on an
            // Android daemon thread is the system loader — app/AndroidX
            // classes (WorkManager, Data$Builder, OwnTV internals) fail with
            // ClassNotFoundException. `bind(name)` resolves through the app's
            // own classloader so EVERY class is reachable, and `new(clazz,
            // ...)` constructs reliably including inner classes ($Builder).
            val appLoader = ctx?.classLoader
            if (appLoader != null) {
                // Make the app loader the thread context loader too, so
                // luajava's own Class.forName (which uses TCCL) works.
                Thread.currentThread().contextClassLoader = appLoader

                globals.set("bind", object : OneArgFunction() {
                    override fun call(name: LuaValue): LuaValue {
                        return try {
                            val cls = Class.forName(name.checkjstring(), true, appLoader)
                            CoerceJavaToLua.coerce(cls)
                        } catch (e: Throwable) {
                            outBuf.appendLine("-- bind(${name.tojstring()}) failed: ${e.message}")
                            LuaValue.NIL
                        }
                    }
                })

                globals.set("new", object : VarArgFunction() {
                    override fun invoke(args: Varargs): Varargs {
                        return try {
                            // LuaJ passes LuaValue userdata, not the raw Class —
                            // `as? Class<*>` on the LuaValue silently fails. Unwrap.
                            val clazz = args.arg(1).touserdata() as? Class<*> ?: return LuaValue.NIL
                            val ctorArgs = (2..args.narg()).map { args.arg(it).checkjstring() }
                            // First try no-arg, then string-arg constructors.
                            val ctor = try {
                                clazz.getConstructor()
                            } catch (_: NoSuchMethodException) {
                                clazz.getConstructor(Array(ctorArgs.size) { String::class.java })
                            }
                            val instance = if (ctor.parameterCount == 0) {
                                ctor.newInstance()
                            } else {
                                ctor.newInstance(*ctorArgs.toTypedArray())
                            }
                            CoerceJavaToLua.coerce(instance)
                        } catch (e: Throwable) {
                            outBuf.appendLine("-- new() failed: ${e.message}")
                            LuaValue.NIL
                        }
                    }
                })
            }

            // log(...)
            globals.set("log", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val sb = StringBuilder()
                    for (i in 1..args.narg()) {
                        if (i > 1) sb.append(' ')
                        sb.append(args.arg(i).tojstring())
                    }
                    val msg = sb.toString()
                    outBuf.appendLine(msg)
                    Log.i(TAG, msg)
                    return LuaValue.NONE
                }
            })

            // exec(cmd)
            globals.set("exec", object : OneArgFunction() {
                override fun call(cmd: LuaValue): LuaValue {
                    return try {
                        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd.checkjstring()))
                        val o = p.inputStream.bufferedReader().readText()
                        val e = p.errorStream.bufferedReader().readText()
                        p.waitFor()
                        outBuf.appendLine("$ ${cmd.tojstring()}")
                        if (o.isNotBlank()) outBuf.append(o)
                        if (e.isNotBlank()) outBuf.append(e)
                        LuaValue.valueOf(p.exitValue())
                    } catch (e: Throwable) {
                        outBuf.appendLine("-- exec error: ${e.message}")
                        LuaValue.NIL
                    }
                }
            })

            customGlobalsHandler?.invoke(globals)

            val chunk = globals.load(script)
            val res = chunk.call()
            LuaResult(true, outBuf.toString(), null)
        } catch (e: Throwable) {
            LuaResult(false, outBuf.toString(), e.message ?: e.toString())
        }
    }
}
