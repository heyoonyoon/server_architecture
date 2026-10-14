package db.migration;

import com.serverarchitecture.PasswordHasher;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

public class V3__create_users extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        user_id VARCHAR(20) PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        role VARCHAR(20) NOT NULL CHECK (role IN ('STUDENT', 'PROFESSOR')),
                        password_hash VARCHAR(100) NOT NULL,
                        salt VARCHAR(100) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE professor (
                        professor_id VARCHAR(20) PRIMARY KEY REFERENCES users (user_id) ON DELETE CASCADE
                    )
                    """);
        }

        Map<String, String> students = new LinkedHashMap<String, String>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT student_id, name FROM student ORDER BY student_id")) {
            while (resultSet.next()) {
                students.put(resultSet.getString("student_id"), resultSet.getString("name"));
            }
        }
        insertUsers(connection, students, "STUDENT");

        Map<String, String> professors = new LinkedHashMap<String, String>();
        professors.put("P001", "Park");
        professors.put("P002", "Kim");
        professors.put("P003", "Ko");
        professors.put("P004", "Ahn");
        professors.put("P005", "Lee");
        insertUsers(connection, professors, "PROFESSOR");

        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO professor (professor_id) VALUES (?)")) {
            for (String professorId : professors.keySet()) {
                statement.setString(1, professorId);
                statement.addBatch();
            }
            statement.executeBatch();
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE student
                        ADD FOREIGN KEY (student_id) REFERENCES users (user_id) ON DELETE CASCADE,
                        DROP COLUMN name
                    """);
        }
    }

    private void insertUsers(Connection connection, Map<String, String> users, String role) throws SQLException {
        String sql = "INSERT INTO users (user_id, name, role, password_hash, salt) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, String> user : users.entrySet()) {
                statement.setString(1, user.getKey());
                statement.setString(2, user.getValue());
                statement.setString(3, role);
                statement.setString(4, PasswordHasher.hash(user.getKey()));
                statement.setString(5, "");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
