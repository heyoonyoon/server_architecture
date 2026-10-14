package com.serverarchitecture;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class UserRepository {
    private final DataSource dataSource;

    public UserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public User findById(String userId) throws SQLException {
        String sql = "SELECT user_id, name, role, password_hash, salt FROM users WHERE user_id = ?";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new User(resultSet.getString("user_id"),
                        resultSet.getString("name"),
                        Role.valueOf(resultSet.getString("role")),
                        resultSet.getString("password_hash"),
                        resultSet.getString("salt"));
            }
        }
    }
}
