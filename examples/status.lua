-- status.lua — probe the running app
local pkg = context.packageName
local pid = android.os.Process.myPid()
local uid = android.os.Process.myUid()

log("package: " .. pkg)
log("pid: " .. pid)
log("uid: " .. uid)

-- return a Lua table (serialized by caller)
return { pkg = pkg, pid = pid, uid = uid }
