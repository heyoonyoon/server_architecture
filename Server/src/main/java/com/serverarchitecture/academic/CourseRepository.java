package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;

public class CourseRepository {
    private final DataClient data;

    public CourseRepository(DataClient data) {
        this.data = data;
    }
}
