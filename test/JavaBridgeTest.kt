package net.luaj.dropin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform

/**
 * TDD for JavaBridge — the classloader-aware bind()/new() Lua globals.
 *
 * These tests exist because the ORIGINAL implementation had a silent bug:
 * `args.arg(1) as? Class<*>` on a LuaValue userdata ALWAYS returned nil, so
 * `new(Data$Builder)` failed without an error and the OwnTV force-sync could
 * not enqueue WorkManager jobs. The unwrap is `args.arg(1).touserdata()`.
 *
 * The tests run on the JVM (no Android) with a sample inner class standing
 * in for androidx.work.Data$Builder — same shape: public no-arg ctor,
 * fluent methods, nested inside an outer class.
 */
class JavaBridgeTest {

    /** Sample outer class with an inner Builder — mirrors Data$Builder shape. */
    class SampleOuter {
        val value: String
        private constructor(v: String) { value = v }

        class Builder {
            private var s = ""
            fun put(k: String, v: String): Builder { s += "$k=$v;"; return this }
            fun build(): SampleOuter = SampleOuter(s)
        }

        companion object {
            @JvmStatic fun of(v: String): SampleOuter = SampleOuter(v)
        }
    }

    /** Target for the class-typed ctor test. */
    class Target

    /** Holder whose ctor takes a Class — mirrors WorkRequest$Builder(WorkerClass). */
    class ClassCtorHolder(val clazz: Class<*>)

    private fun globalsWithBridge(): Pair<Globals, StringBuilder> {
        val globals = JsePlatform.standardGlobals()
        val log = StringBuilder()
        JavaBridge.install(globals, Thread.currentThread().contextClassLoader) { log.appendLine(it) }
        return globals to log
    }

    private fun runLua(globals: Globals, script: String): LuaValue =
        globals.load(script).call()

    @Test
    fun `bind resolves a class through the loader`() {
        val (g, _) = globalsWithBridge()
        val cls = runLua(g, """return bind("java.lang.StringBuilder")""")
        assertNotNull(cls.touserdata(), "bind must return a Class userdata")
        val clazz = cls.touserdata() as? Class<*>
        assertEquals("java.lang.StringBuilder", clazz?.name)
    }

    @Test
    fun `new constructs a no-arg instance`() {
        val (g, _) = globalsWithBridge()
        val sb = runLua(g, """
            local SB = bind("java.lang.StringBuilder")
            local sb = new(SB)
            return sb
        """)
        assertNotNull(sb.touserdata(), "new() must return an instance userdata")
        val instance = sb.touserdata()
        assertTrue(instance is StringBuilder, "expected StringBuilder, got ${instance?.javaClass}")
    }

    @Test
    fun `new constructs with a string ctor`() {
        val (g, _) = globalsWithBridge()
        val sb = runLua(g, """
            local SB = bind("java.lang.StringBuilder")
            local sb = new(SB, "hello")
            return sb
        """)
        val instance = sb.touserdata() as? StringBuilder
        assertEquals("hello", instance?.toString())
    }

    @Test
    fun `new works on inner Builder class`() {
        val (g, _) = globalsWithBridge()
        val builder = runLua(g, """
            local Builder = bind("net.luaj.dropin.JavaBridgeTest${'$'}SampleOuter${'$'}Builder")
            local b = new(Builder)
            b:put("a", "1"):put("b", "2")
            local outer = b:build()
            return outer
        """)
        val instance = builder.touserdata() as? SampleOuter
        assertEquals("a=1;b=2;", instance?.value)
    }

    @Test
    fun `bind of missing class returns nil not throw`() {
        val (g, log) = globalsWithBridge()
        val res = runLua(g, """return bind("com.example.DoesNotExist")""")
        assertTrue(res.isnil(), "missing class must be nil")
        assertTrue(log.contains("bind("), "should log the failure")
    }

    @Test
    fun `new of class with no accessible ctor returns nil not throw`() {
        val (g, log) = globalsWithBridge()
        // java.lang.Class has no public no-arg ctor
        val res = runLua(g, """
            local C = bind("java.lang.Class")
            return new(C)
        """)
        assertTrue(res.isnil(), "no-ctor class must be nil")
        assertTrue(log.contains("new() failed"), "should log the failure")
    }

    @Test
    fun `new with a Class arg uses the class-typed ctor`() {
        val (g, _) = globalsWithBridge()
        // SampleOuter$Builder's ctor is no-arg; use a class-typed ctor case:
        // a holder class whose ctor takes a Class (mirrors WorkRequest$Builder(WorkerClass)).
        val holder = runLua(g, """
            local Holder = bind("net.luaj.dropin.JavaBridgeTest${'$'}ClassCtorHolder")
            local Target = bind("net.luaj.dropin.JavaBridgeTest${'$'}Target")
            local h = new(Holder, Target)
            return h
        """)
        val instance = holder.touserdata() as? ClassCtorHolder
        assertEquals(Target::class.java, instance?.clazz)
    }

    @Test
    fun `instance methods chain through luajava`() {
        val (g, _) = globalsWithBridge()
        val sb = runLua(g, """
            local SB = bind("java.lang.StringBuilder")
            local sb = new(SB)
            sb:append("a"):append("b")
            return sb:toString()
        """)
        assertEquals("ab", sb.tojstring())
    }
}