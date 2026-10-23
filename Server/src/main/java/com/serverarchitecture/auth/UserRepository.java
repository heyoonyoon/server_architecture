package com.serverarchitecture.auth;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.User;

import java.util.Optional;

public class UserRepository {
    private final DataClient data;

    public UserRepository(DataClient data) {
        this.data = data;
    }

    public Optional<User> findById(String userId) {
        throw new UnsupportedOperationException("UserRepository.findById");
    }
}
