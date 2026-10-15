package com.serverarchitecture.redis;

public class RedisNoScriptException extends RedisErrorException {
    public RedisNoScriptException(String message) {
        super(message);
    }
}
