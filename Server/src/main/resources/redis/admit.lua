local now = tonumber(ARGV[1])
local claim = tonumber(ARGV[2])
local max = tonumber(redis.call('GET', KEYS[4])) or tonumber(ARGV[3])
local deadline = now - tonumber(ARGV[4])
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
local dead = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', deadline)
local dropped = 0
for i = 1, #dead do
    dropped = dropped + redis.call('ZREM', KEYS[1], dead[i])
end
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', deadline)
local free = max - redis.call('ZCARD', KEYS[2])
if free <= 0 then
    return {0, dropped}
end
local popped = redis.call('ZPOPMIN', KEYS[1], free)
local admitted = 0
for i = 1, #popped, 2 do
    redis.call('ZADD', KEYS[2], now + claim, popped[i])
    redis.call('ZREM', KEYS[3], popped[i])
    admitted = admitted + 1
end
return {admitted, dropped}
