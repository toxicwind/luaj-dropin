# LuaJPatch — force-hotpatch shim over stock LuaJ

**No fork. No jar surgery. A vendored runtime shim applied at bind time.**

## The problem (proven by bytecode reverse-engineering, 2026-11-10)

LuaJ's `JavaClass` has a public no-arg `getConstructor()` that returns a
callable `JavaConstructor` Overload — but it is **dead code from Lua's
perspective**:

1. `JavaInstance.get()` dispatches through `JavaClass.getMethod(key)`.
2. `getMethod()` lazily builds a map from `Class.getMethods()` — the WRAPPED
   class's methods. `getConstructor` is a JavaClass method, not a
   wrapped-class method, so it never appears.
3. The map keys are **LuaStrings** (not Java Strings — `get(String)` misses,
   `get(LuaString)` hits).
4. When the wrapped class IS `java.lang.Class`, `getConstructor` resolves to
   the reflection `Class.getConstructor(Class...)` — wrong arity,
   "attempt to call nil".

The devs DID wire constructors into the map under `new` (`Class.new(args)`
works natively with full CoerceLuaToJava coercion) — but the `getConstructor`
helper they also built is unreachable.

## The patch

`LuaJPatch.patch(classValue)` runs inside `JavaBridge.bind()` for every bound
class:

1. **Force the lazy build** — call `get("__force_lazy_methods_build__")` so
   the methods map exists (it's null before first access).
2. **Invoke the helper** — `JavaClass.getConstructor()` via reflection with
   `setAccessible(true)` (the method is public but the class is
   package-private → IllegalAccessException otherwise).
3. **Write the LuaString key** — put the JavaConstructor under
   `LuaValue.valueOf("getConstructor")` in the methods map.

## Result

Both constructor idioms work:

```lua
local b = Builder.new(CSW)                 -- native (always worked)
local ctor = Builder["getConstructor"]     -- patched (helper now reachable)
local b = ctor(CSW)
```

**NOTE:** bracket access, NOT colon. `Builder:getConstructor()` passes `self`
as an extra arg which the JavaConstructor Overload rejects ("attempt to call
userdata"). Proven empirically — see JavaBridgeTest.

## Why not fork?

- Stock `luaj-jse:3.0.1` stays the dependency — no maintenance burden, no
  divergence, upstream fixes still arrive.
- The shim is ~40 lines of reflection, TDD-covered, vendored in this repo.
- Applied at bind time in the same `JavaBridge.install()` both hosts call —
  the drop-in and the app stay identical.

## Test coverage (JavaBridgeTest.kt)

- `LuaJPatch makes getConstructor reachable as the devs intended` — class-typed
  ctor via `Holder["getConstructor"](Target)` (the WorkRequest$Builder shape)
- `LuaJPatch getConstructor works on inner Builder` — no-arg ctor on
  `SampleOuter$Builder` (the Data$Builder shape)
- 9/9 green: `./gradlew test`