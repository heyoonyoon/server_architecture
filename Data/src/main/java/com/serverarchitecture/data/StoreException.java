package com.serverarchitecture.data;

import io.grpc.Status;

public class StoreException extends RuntimeException {
    private final Status.Code code;

    public StoreException(Status.Code code, String message) {
        super(message);
        this.code = code;
    }

    public Status.Code code() {
        return code;
    }

    public Status toStatus() {
        return Status.fromCode(code).withDescription(getMessage());
    }
}
