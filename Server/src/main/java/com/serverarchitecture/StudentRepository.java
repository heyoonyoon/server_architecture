package com.serverarchitecture;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

public class StudentRepository {
    private final DataSource dataSource;

    public StudentRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public ArrayList<Student> findAll() throws SQLException {
        String sql = """
                SELECT s.student_id, u.name, s.department, c.course_id
                FROM student s
                JOIN users u ON u.user_id = s.student_id
                LEFT JOIN student_completed_course c ON c.student_id = s.student_id
                ORDER BY s.student_id, c.seq
                """;
        ArrayList<Student> students = new ArrayList<Student>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            Student student = null;
            while (resultSet.next()) {
                String studentId = resultSet.getString("student_id");
                if (student == null || !student.getStudentId().equals(studentId)) {
                    student = new Student(studentId,
                            resultSet.getString("name"),
                            resultSet.getString("department"),
                            new ArrayList<String>());
                    students.add(student);
                }
                String courseId = resultSet.getString("course_id");
                if (courseId != null) {
                    student.getCompletedCourses().add(courseId);
                }
            }
        }
        return students;
    }

    public boolean add(Student student) throws SQLException {
        String userSql = "INSERT INTO users (user_id, name, role, password_hash, salt) VALUES (?, ?, 'STUDENT', ?, ?) ON CONFLICT (user_id) DO NOTHING";
        String studentSql = "INSERT INTO student (student_id, department) VALUES (?, ?)";
        String courseSql = "INSERT INTO student_completed_course (student_id, course_id, seq) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        String salt = PasswordHasher.newSalt();
        String passwordHash = PasswordHasher.hash(student.getStudentId(), salt);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement userStatement = connection.prepareStatement(userSql);
                 PreparedStatement studentStatement = connection.prepareStatement(studentSql);
                 PreparedStatement courseStatement = connection.prepareStatement(courseSql)) {
                userStatement.setString(1, student.getStudentId());
                userStatement.setString(2, student.getName());
                userStatement.setString(3, passwordHash);
                userStatement.setString(4, salt);
                if (userStatement.executeUpdate() == 0) {
                    connection.rollback();
                    return false;
                }

                studentStatement.setString(1, student.getStudentId());
                studentStatement.setString(2, student.getDepartment());
                studentStatement.executeUpdate();

                for (int i = 0; i < student.getCompletedCourses().size(); i++) {
                    courseStatement.setString(1, student.getStudentId());
                    courseStatement.setString(2, student.getCompletedCourses().get(i));
                    courseStatement.setInt(3, i + 1);
                    courseStatement.addBatch();
                }
                courseStatement.executeBatch();
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public boolean delete(String studentId) throws SQLException {
        String sql = "DELETE FROM users WHERE user_id = ? AND role = 'STUDENT'";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentId);
            return statement.executeUpdate() > 0;
        }
    }
}
