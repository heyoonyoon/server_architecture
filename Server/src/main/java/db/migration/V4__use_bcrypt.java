package db.migration;

import com.serverarchitecture.PasswordHasher;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;

public class V4__use_bcrypt extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        ArrayList<String> userIds = new ArrayList<String>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT user_id FROM users ORDER BY user_id")) {
            while (resultSet.next()) {
                userIds.add(resultSet.getString("user_id"));
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET password_hash = ? WHERE user_id = ?")) {
            for (String userId : userIds) {
                statement.setString(1, PasswordHasher.hash(userId));
                statement.setString(2, userId);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE users DROP COLUMN salt");
        }
    }
}
