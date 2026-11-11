-- Force CatalogSyncWorker for all 6 sources — use injected new() helper.
local WM = bind("androidx.work.WorkManager")
local OTR = bind("androidx.work.OneTimeWorkRequest")
local DataBuilder = bind("androidx.work.Data$Builder")
local EWP = bind("androidx.work.ExistingWorkPolicy")
local CSW = bind("tv.own.owntv.core.sync.work.CatalogSyncWorker")

local wm = WM:getInstance(context)

for _, sourceId in ipairs({1, 2, 3, 4, 5, 6}) do
  local d = new(DataBuilder)
  if d == nil then log("DataBuilder new failed for " .. sourceId) else
    d:putLong("sourceId", sourceId)
    d:putString("reason", "force_sync_lua")
    d:putBoolean("live", true)
    d:putBoolean("movies", true)
    d:putBoolean("series", true)
    d:putBoolean("forcePrune", false)
    local payload = d:build()

    local b = new(OTR.Builder)
    if b == nil then log("OTR.Builder new failed for " .. sourceId) else
      b:setInputData(payload)
      local request = b:build()
      wm:enqueueUniqueWork("catalog-sync-source-" .. sourceId, EWP.REPLACE, request)
      log("enqueued sync sourceId=" .. sourceId)
    end
  end
end
return { ok = true }
