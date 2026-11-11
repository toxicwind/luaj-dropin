-- init.lua — STATE RESTORE for the OwnTV Lua lane.
-- Runs at watch start and after every rebuild+relaunch. Idempotent:
-- safe to re-run; only repairs what is missing.
--
-- Restores:
--   1. Single "Main" profile (dedupes duplicate rows)
--   2. The 6 Diamond Xtream sources (inserts if missing)
--   3. profile_source links (profile 1 → all sources)
--
-- Uses the classloader-aware bind() from AppLuaRunner/LuaJDropIn.

local SQLiteDatabase = luajava.bindClass("android.database.sqlite.SQLiteDatabase")
local db = SQLiteDatabase:openDatabase(context:getDatabasePath("owntv.db"):getPath(), nil, 0)

local restored = {}

-- 1. Profile dedupe: keep the lowest id named "Main", delete the rest.
local c = db:rawQuery("SELECT id FROM profiles WHERE name='Main' ORDER BY id", nil)
local ids = {}
while c:moveToNext() do ids[#ids + 1] = c:getLong(0) end
c:close()
if #ids == 0 then
  db:execSQL("INSERT INTO profiles (name) VALUES ('Main')")
  restored[#restored + 1] = "profile Main created"
else
  for i = 2, #ids do
    db:execSQL("DELETE FROM profile_source WHERE profileId=" .. ids[i])
    db:execSQL("DELETE FROM profiles WHERE id=" .. ids[i])
    restored[#restored + 1] = "duplicate profile " .. ids[i] .. " removed"
  end
end

-- Profile 1 is the canonical one.
local profileId = 1

-- 2. Sources: (name, type, url, username, password, sync flags)
local sources = {
  { "1TV41 (HEVC Diamond)", "XTREAM", "http://1xtr.icu:8080", "S09CrA", "514504" },
  { "1TV41 (LG-Proven)", "XTREAM", "http://1xtr.icu:8080", "S09CrA", "514504" },
  { "Tv4Krestream (Netflix HEVC)", "XTREAM", "http://94.130.11.12:8080", "@Tv4Krestream", "krestream" },
  { "RocketDNS US (LG-Proven)", "XTREAM", "http://s.rocketdns.info:8080", "59452816", "59452816" },
  { "New8K (beIN 4K HEVC)", "XTREAM", "http://new8k.cc:8080", "00:7d:3b:5e:89:b6", "00:7d:3b:5e:89:b6" },
  { "99GoPro (beIN Sports 3 HEVC)", "XTREAM", "http://99gopro.org", "4692842534155265", "4692842534155265" },
}

for _, s in ipairs(sources) do
  local name, type_, url, user, pass = s[1], s[2], s[3], s[4], s[5]
  local q = db:rawQuery("SELECT id FROM sources WHERE name=? AND url=?", { name, url })
  local exists = q:moveToFirst()
  local sourceId
  if exists then
    sourceId = q:getLong(0)
  else
    db:execSQL(
      "INSERT INTO sources (name, type, url, username, password, syncLive, syncMovies, syncSeries, importPortalEpg, createdAt) VALUES (?,?,?,?,?,1,1,1,1,?)",
      { name, type_, url, user, pass, os.time() * 1000 }
    )
    local q2 = db:rawQuery("SELECT last_insert_rowid()", nil)
    q2:moveToFirst()
    sourceId = q2:getLong(0)
    q2:close()
    restored[#restored + 1] = "source added: " .. name
  end
  q:close()

  -- 3. profile_source link
  local pc = db:rawQuery("SELECT 1 FROM profile_source WHERE profileId=? AND sourceId=?", { profileId, sourceId })
  if not pc:moveToFirst() then
    db:execSQL("INSERT INTO profile_source (profileId, sourceId) VALUES (?,?)", { profileId, sourceId })
    restored[#restored + 1] = "linked profile " .. profileId .. " → " .. name
  end
  pc:close()
end

db:close()
log("init restore: " .. (#restored == 0 and "nothing needed" or table.concat(restored, ", ")))
return { ok = true, restored = restored }