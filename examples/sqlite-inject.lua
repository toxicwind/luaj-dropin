-- sqlite-inject.lua — inject rows into a live Room/SQLite DB via luajava
local File = luajava.bindClass("java.io.File")
local SQLiteDatabase = luajava.bindClass("android.database.sqlite.SQLiteDatabase")

-- Open the app's own database (context is pre-bound)
local dbPath = context.getDatabasePath("owntv.db")
local db = SQLiteDatabase.openOrCreateDatabase(dbPath, nil)

-- Upsert a source row
local sql = [[
  INSERT OR REPLACE INTO sources (id, name, url, username, password)
  VALUES (?, ?, ?, ?, ?)
]]
db.execSQL(sql, { 7, "Diamond-195", "http://195.128.27.253:2095", "user", "pass" })
log("injected source id=7")

db.close()
return { ok = true }
