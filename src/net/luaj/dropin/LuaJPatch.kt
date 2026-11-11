package net.luaj.dropin

import org.luaj.vm2.LuaValue

/**
 * LuaJPatch — force-hotpatch shim OVER stock LuaJ (no fork).
 *
 * What LuaJ "missed" (proven by bytecode reverse-engineering, 2026-11-10):
 * `JavaClass` has a public no-arg `getConstructor()` that returns a callable
 * `JavaConstructor` Overload — but it is DEAD CODE from Lua's perspective.
 * `JavaInstance.get()` dispatches through `JavaClass.getMethod(key)`, which
 * builds its map from `Class.getMethods()` (the WRAPPED class's methods).
 * `getConstructor` is a JavaClass method, not a wrapped-class method, so
 * `Holder:getConstructor()` from Lua resolves to nil (or, when the wrapped
 * class is java.lang.Class itself, to the reflection
 * `Class.getConstructor(Class...)` — wrong arity, "attempt to call nil").
 *
 * The devs DID wire constructors into the method map under `new`
 * (`Class.new(args)` works natively) — but the `getConstructor` helper they
 * also built is unreachable. This shim force-hotpatches the `methods` map
 * of every JavaClass at bind time so BOTH idioms work:
 *
 *   local b = Builder.new(CSW)              -- native (already worked)
 *   local b = Builder:getConstructor()(CSW) -- patched (helper now reachable)
 *
 * The patch is applied at bind() time (see JavaBridge) — vendored, no fork,
 * no jar surgery. Pure JVM, TDD-covered by JavaBridgeTest.
 */
object LuaJPatch {

    private val methodsField by lazy {
        Class.forName("org.luaj.vm2.lib.jse.JavaClass")
            .getDeclaredField("methods")
            .apply { isAccessible = true }
    }

    /**
     * Force-hotpatch a bound JavaClass LuaValue: make `getConstructor`
     * resolve to the callable JavaConstructor helper.
     *
     * @return true when patched, false when the value isn't a JavaClass.
     */
    fun patch(classValue: LuaValue): Boolean {
        return try {
            val jc = classValue.javaClass
            if (jc.name != "org.luaj.vm2.lib.jse.JavaClass") return false

            // Force the LAZY methods-map build: getMethod(key) builds the map
            // from Class.getMethods() on first access. Without this the map is
            // null and the patch has nowhere to write.
            classValue.get("__force_lazy_methods_build__")
            val methods = methodsField.get(classValue) as? MutableMap<*, *> ?: return false

            // Call the (public method on a package-private class) helper directly —
            // needs setAccessible because JavaClass itself is package-private.
            val helperMethod = jc.getMethod("getConstructor")
            helperMethod.isAccessible = true
            val helper = helperMethod.invoke(classValue) as LuaValue
            if (helper.isnil()) return false

            @Suppress("UNCHECKED_CAST")
            // Map keys are LuaStrings (proven: get(String) misses,
            // get(LuaString) hits — the bytecode checkcast was on an
            // intermediate, not the final map keys). Write with LuaString.
            (methods as MutableMap<Any, Any>)[LuaValue.valueOf("getConstructor")] = helper
            true
        } catch (_: Throwable) {
            false
        }
    }
}