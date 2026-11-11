-- force-sync.lua — enqueue CatalogSyncWorker for ALL sources (state restore lane).
-- Uses the TDD-proven JavaBridge: bind() classloader bridge + new() exact-ctor
-- (bypasses LuaJ's Overload which picks the no-arg ctor over the Class ctor).
local WM = bind("androidx.work.WorkManager")
local Builder = bind("androidx.work.OneTimeWorkRequest$Builder")
local DataBuilder = bind("androidx.work.Data$Builder")
local EWP = bind("androidx.work.ExistingWorkPolicy")
local CSW = bind("tv.own.owntv.core.sync.work.CatalogSyncWorker")
local wm = WM:getInstance(context)

-- Discover sources from the DB (not hardcoded).
local SQLiteDatabase = luajava.bindClass("android.database.sqlite.SQLiteDatabase")
local db = SQLiteDatabase:openDatabase(context:getDatabasePath("owntv.db"):getPath(), nil, 0)
local c = db:rawQuery("SELECT id FROM sources", nil)
local ids = {}
while c:moveToNext() do ids[#ids + 1] = c:getLong(0) end
c:close()
db:close()

for _, sourceId in ipairs(ids) do
  local d = new(DataBuilder)
  d:putLong("sourceId", sourceId)
  d:putString("reason", "force_sync_lua")
  d:putBoolean("live", true)
  d:putBoolean("movies", true)
  d:putBoolean("series", true)
  d:putBoolean("forcePrune", false)
  local payload = d:build()
  local b = new(Builder, CSW)
  b:setInputData(payload)
  local request = b:build()
  wm:enqueueUniqueWork("catalog-sync-source-" .. sourceId, EWP.REPLACE, request)
  log("ENQUEUED sourceId=" .. sourceId)
end
return { ok = true, sources = #ids }
