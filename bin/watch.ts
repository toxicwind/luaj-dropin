#!/usr/bin/env bun
/**
 * watch.ts — LuaJ drop-in AUTO-DEPLOY WATCHER (whole workspace).
 *
 * Two lanes:
 *   1. LUA LANE — any .lua in lua/ → POST /run to the device (:38383).
 *      Instant: edit → saved → running on device.
 *   2. SOURCE LANE — any change under src/ (Kotlin) → gradle assemble
 *      StandardDebug → adb push + pm install -r → relaunch.
 *      The full edit→build→deploy loop for the APK itself.
 *
 * Usage:
 *   bun bin/watch.ts [--lua-dir lua] [--src-dir src] [--host http://127.0.0.1:38383]
 *   bun bin/watch.ts --no-source   # Lua only
 *   bun bin/watch.ts --no-lua      # source only
 *
 * Conventions:
 *   - lua files starting with `_` are skipped (shared libs).
 *   - Source changes are debounced 1.5s (gradle is the bottleneck, not us).
 *   - Build output goes to .watch-build.log; failures print the tail.
 */
import { watch } from "node:fs";
import { join, basename, dirname } from "node:path";
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { spawn } from "node:child_process";

const args = process.argv.slice(2);
const luaDir = args[args.indexOf("--lua-dir") + 1] ?? "lua";
const srcDir = args[args.indexOf("--src-dir") + 1] ?? "src";
const hostIdx = args.indexOf("--host");
const host = hostIdx >= 0 ? args[hostIdx + 1] : "http://127.0.0.1:38383";
const wantLua = !args.includes("--no-lua");
const wantSource = !args.includes("--no-source");

const ADB = "/usr/bin/adb";
const TV = "10.0.0.225:5555";
const APK = "app/build/outputs/apk/standard/debug/app-standard-debug.apk";
const BUILD_LOG = ".watch-build.log";

// ── Lua lane ──
async function deployLua(file: string, reason: string): Promise<void> {
  const script = readFileSync(file, "utf8");
  const label = basename(file);
  const t0 = Date.now();
  try {
    const res = await fetch(`${host}/run`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ script }),
      signal: AbortSignal.timeout(15000),
    });
    const json = (await res.json()) as { ok?: boolean; output?: string; error?: string | null };
    const ms = Date.now() - t0;
    if (json.ok) {
      console.log(`✓ lua ${label} (${reason}) ${ms}ms`);
      if (json.output?.trim()) console.log(json.output.trim().split("\n").map((l) => `  ${l}`).join("\n"));
    } else {
      console.error(`✗ lua ${label} (${reason}) ${ms}ms — ${json.error ?? "unknown"}`);
      if (json.output?.trim()) console.error(json.output.trim().split("\n").map((l) => `  ${l}`).join("\n"));
    }
  } catch (e) {
    console.error(`✗ lua ${label} (${reason}) — ${(e as Error).message}`);
  }
}

/**
 * STATE RESTORE — after a rebuild+relaunch the Lua runtime is fresh: every
 * injected global, DB handle and listener is gone. Re-run the restore pass:
 *   1. lua/init.lua first (idempotent state: sources, prefs, DB rows)
 *   2. then every other non-underscore .lua in order.
 * The watcher also runs this at startup, so a crashed lane heals on watch.
 */
async function restoreState(reason: string): Promise<void> {
  if (!wantLua || !existsSync(luaDir)) return;
  console.log(`\n♻️  state restore (${reason})…`);
  const files = readdirSync(luaDir).filter((f) => f.endsWith(".lua") && !f.startsWith("_"));
  const ordered = [
    ...files.filter((f) => f === "init.lua"),
    ...files.filter((f) => f !== "init.lua"),
  ];
  for (const f of ordered) {
    await deployLua(join(luaDir, f), reason);
  }
}

// ── Source lane: gradle → adb → install → relaunch ──
let building = false;
let buildQueued = false;

async function buildAndDeploy(reason: string): Promise<void> {
  if (building) {
    buildQueued = true;
    return;
  }
  building = true;
  const t0 = Date.now();
  console.log(`\n🔨 build (${reason})…`);
  try {
    await runCmd("bun", ["x", "gradle", ":app:assembleStandardDebug", "--offline"], BUILD_LOG);
    const ms = Date.now() - t0;
    console.log(`✓ build ${ms}ms`);
    await runCmd(ADB, ["-s", TV, "push", APK, "/data/local/tmp/owntv.apk"], BUILD_LOG);
    await runCmd(ADB, ["-s", TV, "shell", "pm install -r /data/local/tmp/owntv.apk"], BUILD_LOG);
    await runCmd(ADB, ["-s", TV, "shell", "am start -n tv.own.owntv/.MainActivity"], BUILD_LOG);
    console.log("✓ deployed + relaunched");
    // State restore: the fresh process has no Lua globals, DB handles or
    // listeners — re-run init.lua + the rest to rebuild the runtime state.
    await waitForLane();
    await restoreState("after_launch");
  } catch (e) {
    console.error(`✗ build/deploy failed: ${(e as Error).message}`);
    console.error(`  log: ${BUILD_LOG}`);
  } finally {
    building = false;
    if (buildQueued) {
      buildQueued = false;
      void buildAndDeploy("queued");
    }
  }
}

function runCmd(cmd: string, args: string[], logFile: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const proc = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"] });
    const chunks: Buffer[] = [];
    proc.stdout.on("data", (d) => chunks.push(d));
    proc.stderr.on("data", (d) => chunks.push(d));
    proc.on("close", (code) => {
      const out = Buffer.concat(chunks).toString("utf8");
      try {
        const fs = require("node:fs");
        fs.writeFileSync(logFile, out);
      } catch {}
      if (code === 0) resolve();
      else {
        const tail = out.split("\n").slice(-15).join("\n");
        reject(new Error(`${cmd} exit ${code}\n${tail}`));
      }
    });
  });
}

/** Poll the Lua lane until it answers (fresh process needs a moment). */
async function waitForLane(attempts = 20): Promise<void> {
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetch(`${host}/`, { signal: AbortSignal.timeout(2000) });
      if (res.ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  console.warn("⚠ lane did not answer after launch — restore may fail");
}

// ── Initial pass: full state restore at watch start ──
await restoreState("watch_start");

console.log(`\nWatching:`);
if (wantLua) console.log(`  lua:  ${luaDir}/ → ${host}/run (instant)`);
if (wantSource) console.log(`  src:  ${srcDir}/ → gradle → adb → TV (debounced 1.5s)`);
console.log("  (files starting with _ skipped; Ctrl-C to stop)\n");

const luaPending = new Map<string, NodeJS.Timeout>();
let srcTimer: NodeJS.Timeout | null = null;

if (wantLua && existsSync(luaDir)) {
  watch(luaDir, { recursive: false }, (_e, filename) => {
    if (!filename || !filename.toString().endsWith(".lua")) return;
    const name = filename.toString();
    if (name.startsWith("_")) return;
    const full = join(luaDir, name);
    if (!existsSync(full)) return;
    const existing = luaPending.get(name);
    if (existing) clearTimeout(existing);
    luaPending.set(name, setTimeout(() => {
      luaPending.delete(name);
      void deployLua(full, "changed");
    }, 250));
  });
}

if (wantSource && existsSync(srcDir)) {
  watch(srcDir, { recursive: true }, (_e, filename) => {
    if (!filename) return;
    const name = filename.toString();
    if (!/\.(kt|java|xml|kts|toml)$/.test(name)) return;
    if (srcTimer) clearTimeout(srcTimer);
    srcTimer = setTimeout(() => void buildAndDeploy(`src: ${name}`), 1500);
  });
}

void dirname; // keep import used when --no-source