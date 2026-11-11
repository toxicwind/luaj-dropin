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
                    val coerced = CoerceJavaToLua.coerce(cls)
                    // Force-hotpatch: make JavaClass.getConstructor() reachable
                    // from Lua (vendored shim, no fork — see LuaJPatch).
                    LuaJPatch.patch(coerced, log)
                    coerced
                } catch (e: Throwable) {
                    log("-- bind(${name.tojstring()}) failed: ${e.message}")
                    LuaValue.NIL
                }
            }
        })

        globals.set("new", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                return try {
                    val cls = args.arg(1)
                    // LuaJ's Overload picks the LOWEST-scoring ctor; for
                    // WorkRequest$Builder the no-arg ctor (score 0) beats the
                    // (Class) ctor (score 1), so Class-typed construction
                    // silently picks the wrong overload. Bypass the Overload:
                    // resolve the exact ctor by param types via reflection.
                    val clazz = cls.touserdata() as? Class<*> ?: return LuaValue.NIL
                    val ctorArgs = (2..args.narg()).map { args.arg(it) }
                    val ctor = if (ctorArgs.isEmpty()) {
                        clazz.getConstructor()
                    } else {
                        // Match param types: Class for JavaClass userdata,
                        // String for strings, else Object.
                        val types = ctorArgs.map { v ->
                            when {
                                v.touserdata() is Class<*> -> Class::class.java
                                v.type() == LuaValue.TSTRING -> String::class.java
                                else -> Any::class.java
                            }
                        }.toTypedArray()
                        clazz.getConstructor(*types)
                    }
                    val javaArgs = ctorArgs.map { v ->
                        v.touserdata() as? Class<*> ?: v.checkjstring()
                    }.toTypedArray()
                    CoerceJavaToLua.coerce(ctor.newInstance(*javaArgs))
                } catch (e: Throwable) {
                    log("-- new() failed: ${e.message}")
                    LuaValue.NIL
                }
            }
        })
    }
}