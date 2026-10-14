package com.serverarchitecture;

import io.grpc.Status;

public class CustomException extends RuntimeException {
    private final Status.Code code;

    public CustomException(Status.Code code, String message) {
        super(message);
        this.code = code;
    }

    public Status toStatus() {
        return Status.fromCode(code).withDescription(getMessage());
    }
}
