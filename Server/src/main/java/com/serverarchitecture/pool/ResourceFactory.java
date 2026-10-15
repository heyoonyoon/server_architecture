package com.serverarchitecture.pool;

public interface ResourceFactory<T> {
    T create() throws Exception;

    boolean validate(T resource);

    void destroy(T resource) throws Exception;
}
