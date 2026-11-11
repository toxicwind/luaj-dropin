-- dump-dex.lua — trigger a BlackDex dump of another installed app
local BlackDexCore = luajava.bindClass("top.niunaijun.blackbox.BlackDexCore")

local target = "com.playtorrio.tv"
local res = BlackDexCore:get():dumpDex(target)

if res and res.success then
  log("dump started for " .. target .. " → " .. res.packageName)
  return { ok = true, pkg = res.packageName }
else
  log("dump failed for " .. target)
  return { ok = false }
end
