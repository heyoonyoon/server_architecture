package com.serverarchitecture.redis;

public class RedisConnectionException extends RedisException {
    public RedisConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
