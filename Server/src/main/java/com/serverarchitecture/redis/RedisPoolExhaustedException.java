package com.serverarchitecture.redis;

public class RedisPoolExhaustedException extends RedisException {
    public RedisPoolExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
