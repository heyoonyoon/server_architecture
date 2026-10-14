if redis.call('SREM', KEYS[2], ARGV[1]) == 0 then
    return 0
end
redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
return 1
