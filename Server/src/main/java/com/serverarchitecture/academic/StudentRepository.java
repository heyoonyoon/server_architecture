package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;

public class StudentRepository {
    private final DataClient data;

    public StudentRepository(DataClient data) {
        this.data = data;
    }
}
