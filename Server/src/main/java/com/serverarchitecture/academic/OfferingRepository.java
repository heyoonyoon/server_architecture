package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;

public class OfferingRepository {
    private final DataClient data;

    public OfferingRepository(DataClient data) {
        this.data = data;
    }
}
