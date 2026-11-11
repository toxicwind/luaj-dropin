package net.luaj.dropin

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua

/**
 * JavaBridge — classloader-aware Java interop for LuaJ, pure JVM (no Android).
 *
 * Plain `luajava.bindClass` uses the DEFAULT classloader, which on an Android
 * daemon thread is the system loader — app/AndroidX classes fail with
 * ClassNotFoundException. This installs two globals that resolve through the
 * app's own classloader:
 *
 *   bind(name)  — Class.forName(name, true, appLoader) → Lua userdata Class
 *   new(clazz)  — construct an instance (handles inner classes like `Data$Builder`)
 *
 * Both LuaJDropIn (the drop-in) and AppLuaRunner (the app's runner) install
 * these globals; the implementation lives HERE so it is testable on the JVM
 * and stays identical across both hosts.
 *
 * TDD contract (test/JavaBridgeTest.kt):
 *   - bind("java.lang.StringBuilder") returns a usable class userdata
 *   - new(boundClass) constructs an instance (no-arg ctor)
 *   - new(boundClass, "x") constructs with a String ctor
 *   - bind of an inner class (java.util.Map$Entry) + new() works
 *   - bind of a missing class returns nil, never throws
 *   - new() of a class with no accessible ctor returns nil, never throws
 */
object JavaBridge {

    fun install(globals: Globals, appLoader: ClassLoader, log: (String) -> Unit) {
        // Make the app loader the thread context loader too, so luajava's own
        // Class.forName (which uses TCCL) works as a fallback.
        Thread.currentThread().contextClassLoader = appLoader

        globals.set("bind", object : OneArgFunction() {
            override fun call(name: LuaValue): LuaValue {
                return try {
                    val cls = Class.forName(name.checkjstring(), true, appLoader)
                    CoerceJavaToLua.coerce(cls)
                } catch (e: Throwable) {
                    log("-- bind(${name.tojstring()}) failed: ${e.message}")
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
                    val ctor = if (ctorArgs.isEmpty()) {
                        clazz.getConstructor()
                    } else {
                        clazz.getConstructor(*Array(ctorArgs.size) { String::class.java })
                    }
                    val instance = if (ctor.parameterCount == 0) {
                        ctor.newInstance()
                    } else {
                        ctor.newInstance(*ctorArgs.toTypedArray())
                    }
                    CoerceJavaToLua.coerce(instance)
                } catch (e: Throwable) {
                    log("-- new() failed: ${e.message}")
                    LuaValue.NIL
                }
            }
        })
    }
}