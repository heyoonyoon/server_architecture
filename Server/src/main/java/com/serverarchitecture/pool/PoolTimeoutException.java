package com.serverarchitecture.pool;

public class PoolTimeoutException extends Exception {
    public PoolTimeoutException(String message) {
        super(message);
    }
}
