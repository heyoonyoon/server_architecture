if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end
local remaining = redis.call('HGET', KEYS[1], ARGV[1])
if not remaining then
    return -3
end
if tonumber(remaining) <= 0 then
    return -2
end
redis.call('HINCRBY', KEYS[1], ARGV[1], -1)
redis.call('SADD', KEYS[2], ARGV[1])
return 1
