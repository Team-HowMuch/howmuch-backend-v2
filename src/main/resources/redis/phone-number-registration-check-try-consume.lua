local addressCount = 0
local phoneCount = 0

local addressValue = redis.call('GET', KEYS[1])
if addressValue then
    addressCount = tonumber(addressValue)
    if not addressCount or redis.call('PTTL', KEYS[1]) == -1 then
        return -1
    end
end

local phoneValue = redis.call('GET', KEYS[2])
if phoneValue then
    phoneCount = tonumber(phoneValue)
    if not phoneCount or redis.call('PTTL', KEYS[2]) == -1 then
        return -1
    end
end

if addressCount >= tonumber(ARGV[1]) or phoneCount >= tonumber(ARGV[2]) then
    return 0
end

addressCount = redis.call('INCR', KEYS[1])
if addressCount == 1 then
    redis.call('PEXPIRE', KEYS[1], ARGV[3])
end

phoneCount = redis.call('INCR', KEYS[2])
if phoneCount == 1 then
    redis.call('PEXPIRE', KEYS[2], ARGV[3])
end

return 1
