-- protobuf.lua — pure-Lua protobuf wire-format encoder/decoder (Lua 5.2+).
-- No C, no luajava: usable anywhere LuaJ runs (the dropin lane, the TV).
--
-- Supports the full wire format:
--   varint, fixed32, fixed64, length-delimited, nested messages,
--   repeated fields, maps (as repeated map-entry messages).
--
-- API:
--   proto.encode(msg) -> string        msg = { field_number = value, ... }
--   proto.decode(bytes) -> msg         value shapes:
--     number            -> varint (int32/int64/bool/enum)
--     string            -> length-delimited
--     { fixed32 = n }   -> fixed32
--     { fixed64 = n }   -> fixed64
--     { msg = {...} }   -> nested message
--     { repeated = {...} } -> repeated field
--     { map = { k = v, ... } } -> map<string,...> (entry messages)
--
-- Wire types: 0=varint 1=fixed64 2=len 5=fixed32

local M = {}

local function varint_encode(n)
  n = n % 2^64
  local out = {}
  while n >= 0x80 do
    out[#out + 1] = string.char((n % 0x80) + 0x80)
    n = math.floor(n / 0x80)
  end
  out[#out + 1] = string.char(n)
  return table.concat(out)
end

local function zigzag(n)
  return (n * 2) % 2^64 -- caller handles negatives via 2^64+n
end

local function key_encode(field, wire)
  return varint_encode(field * 8 + wire)
end

local function fixed32_encode(n)
  n = n % 2^32
  return string.char(n % 256, math.floor(n / 256) % 256, math.floor(n / 65536) % 256, math.floor(n / 16777216) % 256)
end

local function fixed64_encode(n)
  local lo = n % 2^32
  local hi = math.floor(n / 2^32) % 2^32
  return fixed32_encode(lo) .. fixed32_encode(hi)
end

-- Encode one value for a field. `field` is the field number.
local function encode_value(field, v)
  if type(v) == "number" then
    local n = v
    if n < 0 then n = 2^64 + n end
    return key_encode(field, 0) .. varint_encode(n)
  elseif type(v) == "boolean" then
    return key_encode(field, 0) .. varint_encode(v and 1 or 0)
  elseif type(v) == "string" then
    return key_encode(field, 2) .. varint_encode(#v) .. v
  elseif type(v) == "table" then
    if v.fixed32 then
      return key_encode(field, 5) .. fixed32_encode(v.fixed32)
    elseif v.fixed64 then
      return key_encode(field, 1) .. fixed64_encode(v.fixed64)
    elseif v.msg then
      local inner = M.encode(v.msg)
      return key_encode(field, 2) .. varint_encode(#inner) .. inner
    elseif v.repeated then
      local out = {}
      for _, item in ipairs(v.repeated) do
        out[#out + 1] = encode_value(field, item)
      end
      return table.concat(out)
    elseif v.map then
      local out = {}
      for k, val in pairs(v.map) do
        -- map entry: field 1 = key (string), field 2 = value
        local entry = {}
        entry[1] = k
        entry[2] = val
        local inner = M.encode(entry)
        out[#out + 1] = key_encode(field, 2) .. varint_encode(#inner) .. inner
      end
      return table.concat(out)
    else
      -- plain table = nested message fields keyed by number
      local inner = M.encode(v)
      return key_encode(field, 2) .. varint_encode(#inner) .. inner
    end
  end
  error("protobuf: unsupported value type for field " .. field)
end

function M.encode(msg)
  local out = {}
  for field, v in pairs(msg) do
    if type(field) == "number" then
      out[#out + 1] = encode_value(field, v)
    end
  end
  return table.concat(out)
end

-- ── Decoder ──
local function varint_decode(bytes, pos)
  local result = 0
  local shift = 0
  while true do
    local b = bytes:byte(pos)
    if not b then error("protobuf: truncated varint") end
    pos = pos + 1
    result = result + (b % 0x80) * 2^shift
    if b < 0x80 then break end
    shift = shift + 7
    if shift > 63 then error("protobuf: varint too long") end
  end
  return result, pos
end

function M.decode(bytes)
  local msg = {}
  local pos = 1
  while pos <= #bytes do
    local key, np = varint_decode(bytes, pos)
    pos = np
    local field = math.floor(key / 8)
    local wire = key % 8
    if wire == 0 then
      local v, p2 = varint_decode(bytes, pos)
      msg[field] = v
      pos = p2
    elseif wire == 1 then
      msg[field] = { fixed64 = bytes:byte(pos) + bytes:byte(pos+1) * 2^8 + bytes:byte(pos+2) * 2^16 + bytes:byte(pos+3) * 2^24 }
      pos = pos + 8
    elseif wire == 2 then
      local len, p2 = varint_decode(bytes, pos)
      local str = bytes:sub(p2, p2 + len - 1)
      -- try nested decode: if it parses cleanly as a message, decode it
      local ok, nested = pcall(M.decode, str)
      if ok and next(nested) then
        if msg[field] then
          if not msg[field].repeated then msg[field] = { repeated = { msg[field] } } end
          msg[field].repeated[#msg[field].repeated + 1] = { msg = nested }
        else
          msg[field] = { msg = nested }
        end
      else
        if msg[field] then
          if not msg[field].repeated then msg[field] = { repeated = { msg[field] } } end
          msg[field].repeated[#msg[field].repeated + 1] = str
        else
          msg[field] = str
        end
      end
      pos = p2 + len
    elseif wire == 5 then
      msg[field] = { fixed32 = bytes:byte(pos) + bytes:byte(pos+1) * 2^8 + bytes:byte(pos+2) * 2^16 + bytes:byte(pos+3) * 2^24 }
      pos = pos + 4
    else
      error("protobuf: unsupported wire type " .. wire)
    end
  end
  return msg
end

-- ── Float helpers (DataStore uses float = fixed32) ──
-- Encode a Lua number as IEEE-754 single-precision fixed32.
local function float32(n)
  if n ~= n then return 0x7FC00000 end -- NaN
  if n == math.huge then return 0x7F800000 end
  if n == -math.huge then return 0xFF800000 end
  local sign = 0
  if n < 0 then sign = 0x80000000; n = -n end
  local exp = 0
  if n >= 2 then
    while n >= 2 do n = n / 2; exp = exp + 1 end
  elseif n < 1 then
    while n < 1 do n = n * 2; exp = exp - 1 end
  end
  local mant = math.floor(n * 2^23 + 0.5)
  if mant >= 2^24 then mant = mant / 2; exp = exp + 1 end
  return sign + (exp + 127) * 2^23 + (mant - 2^23)
end

M.float32 = float32

return M