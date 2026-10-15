package com.serverarchitecture.redis;

public class RedisErrorException extends RedisException {
    public RedisErrorException(String message) {
        super(message);
    }

    static RedisErrorException of(String message) {
        return message.startsWith("NOSCRIPT") ? new RedisNoScriptException(message) : new RedisErrorException(message);
    }
}
