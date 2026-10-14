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
                SELECT s.student_id, u.name, d.department_id AS department, c.course_id
                FROM student s
                JOIN users u ON u.user_id = s.student_id
                LEFT JOIN student_department d ON d.student_id = s.student_id AND d.major_type = 'MAJOR'
                LEFT JOIN (
                    SELECT DISTINCT e.student_id, o.course_id
                    FROM enrollment e
                    JOIN course_offering o ON o.offering_id = e.offering_id
                    WHERE e.status = 'COMPLETED'
                ) c ON c.student_id = s.student_id
                ORDER BY s.student_id, c.course_id
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
        String userSql = "INSERT INTO users (user_id, name, role, password_hash) VALUES (?, ?, 'STUDENT', ?) ON CONFLICT (user_id) DO NOTHING";
        String studentSql = "INSERT INTO student (student_id) VALUES (?)";
        String departmentSql = "INSERT INTO department (department_id, name) VALUES (?, ?) ON CONFLICT (department_id) DO NOTHING";
        String majorSql = "INSERT INTO student_department (student_id, department_id, major_type) VALUES (?, ?, 'MAJOR')";
        String courseSql = """
                INSERT INTO enrollment (student_id, offering_id, status, grade)
                SELECT ?, offering_id, 'COMPLETED', 'P'
                FROM course_offering
                WHERE course_id = ? AND semester <> ?
                ORDER BY semester DESC, section
                LIMIT 1
                ON CONFLICT DO NOTHING
                """;
        String passwordHash = PasswordHasher.hash(student.getStudentId());

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement userStatement = connection.prepareStatement(userSql);
                 PreparedStatement studentStatement = connection.prepareStatement(studentSql);
                 PreparedStatement departmentStatement = connection.prepareStatement(departmentSql);
                 PreparedStatement majorStatement = connection.prepareStatement(majorSql);
                 PreparedStatement courseStatement = connection.prepareStatement(courseSql)) {
                userStatement.setString(1, student.getStudentId());
                userStatement.setString(2, student.getName());
                userStatement.setString(3, passwordHash);
                if (userStatement.executeUpdate() == 0) {
                    connection.rollback();
                    return false;
                }

                studentStatement.setString(1, student.getStudentId());
                studentStatement.executeUpdate();

                departmentStatement.setString(1, student.getDepartment());
                departmentStatement.setString(2, student.getDepartment());
                departmentStatement.executeUpdate();

                majorStatement.setString(1, student.getStudentId());
                majorStatement.setString(2, student.getDepartment());
                majorStatement.executeUpdate();

                for (String courseId : student.getCompletedCourses()) {
                    courseStatement.setString(1, student.getStudentId());
                    courseStatement.setString(2, courseId);
                    courseStatement.setString(3, EnrollmentConfig.SEMESTER);
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
