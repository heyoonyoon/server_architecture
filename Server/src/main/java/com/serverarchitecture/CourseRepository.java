package com.serverarchitecture;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

public class CourseRepository {
    private final DataSource dataSource;

    public CourseRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public ArrayList<Course> findAll() throws SQLException {
        String sql = """
                SELECT c.course_id, c.name, c.instructor_name, p.prerequisite_course_id
                FROM course c
                LEFT JOIN course_prerequisite p ON p.course_id = c.course_id
                ORDER BY c.course_id, p.seq
                """;
        ArrayList<Course> courses = new ArrayList<Course>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            Course course = null;
            while (resultSet.next()) {
                String courseId = resultSet.getString("course_id");
                if (course == null || !course.getCourseId().equals(courseId)) {
                    course = new Course(courseId,
                            resultSet.getString("name"),
                            resultSet.getString("instructor_name"),
                            new ArrayList<String>());
                    courses.add(course);
                }
                String prerequisiteCourseId = resultSet.getString("prerequisite_course_id");
                if (prerequisiteCourseId != null) {
                    course.getPrerequisiteCourses().add(prerequisiteCourseId);
                }
            }
        }
        return courses;
    }

    public boolean add(Course course) throws SQLException {
        String courseSql = "INSERT INTO course (course_id, name, instructor_name) VALUES (?, ?, ?) ON CONFLICT (course_id) DO NOTHING";
        String prerequisiteSql = "INSERT INTO course_prerequisite (course_id, prerequisite_course_id, seq) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement courseStatement = connection.prepareStatement(courseSql);
                 PreparedStatement prerequisiteStatement = connection.prepareStatement(prerequisiteSql)) {
                courseStatement.setString(1, course.getCourseId());
                courseStatement.setString(2, course.getName());
                courseStatement.setString(3, course.getInstructorName());
                if (courseStatement.executeUpdate() == 0) {
                    connection.rollback();
                    return false;
                }

                for (int i = 0; i < course.getPrerequisiteCourses().size(); i++) {
                    prerequisiteStatement.setString(1, course.getCourseId());
                    prerequisiteStatement.setString(2, course.getPrerequisiteCourses().get(i));
                    prerequisiteStatement.setInt(3, i + 1);
                    prerequisiteStatement.addBatch();
                }
                prerequisiteStatement.executeBatch();
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

    public boolean delete(String courseId) throws SQLException {
        String sql = "DELETE FROM course WHERE course_id = ?";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId);
            return statement.executeUpdate() > 0;
        }
    }
}
