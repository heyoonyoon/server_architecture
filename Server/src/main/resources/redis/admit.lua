local now = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])
local max = tonumber(ARGV[3])
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
local free = max - redis.call('ZCARD', KEYS[2])
if free <= 0 then
    return 0
end
local popped = redis.call('ZPOPMIN', KEYS[1], free)
local admitted = 0
for i = 1, #popped, 2 do
    redis.call('ZADD', KEYS[2], now + ttl, popped[i])
    admitted = admitted + 1
end
return admitted
