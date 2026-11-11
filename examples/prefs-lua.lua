-- prefs-lua.lua — read/write SharedPreferences
local prefs = context.getSharedPreferences("owntv_prefs", 0)

-- read
local current = prefs.getString("profile", "none")
log("current profile: " .. current)

-- write
prefs.edit().putString("profile", "Main").putBoolean("setup_done", true).apply()
log("profile set to Main")
return { profile = "Main", setup_done = true }
