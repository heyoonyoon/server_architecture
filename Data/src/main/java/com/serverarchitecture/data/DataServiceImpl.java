package com.serverarchitecture.data;

import com.serverarchitecture.data.api.DataServiceGrpc;

public class DataServiceImpl extends DataServiceGrpc.DataServiceImplBase {
    private final Store store;

    public DataServiceImpl(Store store) {
        this.store = store;
    }
}
