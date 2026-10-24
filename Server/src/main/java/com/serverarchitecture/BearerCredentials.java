package com.serverarchitecture;

import io.grpc.CallCredentials;
import io.grpc.Metadata;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class BearerCredentials extends CallCredentials {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Supplier<String> token;

    public BearerCredentials(Supplier<String> token) {
        this.token = token;
    }

    @Override
    public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + token.get());
        applier.apply(headers);
    }
}
