package com.serverarchitecture;

import com.serverarchitecture.data.api.Item;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class UserRepository {
    private final DataClient data;

    public UserRepository(DataClient data) {
        this.data = data;
    }

    public Optional<User> findById(String userId) {
        return data.read(DataCollections.USERS, userId).map(UserRepository::toUser);
    }

    public List<User> findByRole(Role role) {
        return data.readWhere(DataCollections.USERS, "role", role.name()).stream().map(UserRepository::toUser).toList();
    }

    public void create(User user) {
        data.create(DataCollections.USERS, DataClient.item(user.getUserId(), Map.of(
                "name", user.getName(),
                "role", user.getRole().name(),
                "password_hash", user.getPasswordHash())));
    }

    public boolean delete(String userId) {
        return data.delete(DataCollections.USERS, userId);
    }

    private static User toUser(Item item) {
        Map<String, String> fields = item.getFieldsMap();
        return new User(item.getId(),
                fields.getOrDefault("name", ""),
                Role.valueOf(fields.getOrDefault("role", "STUDENT")),
                fields.getOrDefault("password_hash", ""));
    }
}
