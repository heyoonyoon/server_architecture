package com.serverarchitecture;

import io.grpc.Status;

public class NullDataException extends CustomException {
    public NullDataException(String message) {
        super(Status.Code.NOT_FOUND, message);
    }
}
