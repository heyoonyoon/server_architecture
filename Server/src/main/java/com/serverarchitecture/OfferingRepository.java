package com.serverarchitecture;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class OfferingRepository {
    private final DataSource dataSource;

    public OfferingRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Map<Integer, Offering> findBySemester(String semester) throws SQLException {
        String offeringSql = """
                SELECT o.offering_id, o.course_id, c.name AS course_name, c.credits, o.semester, o.section,
                       o.professor_id, u.name AS professor_name, o.room, o.capacity,
                       (SELECT COUNT(*) FROM enrollment e
                        WHERE e.offering_id = o.offering_id AND e.status = 'ENROLLED') AS enrolled_count
                FROM course_offering o
                JOIN course c ON c.course_id = o.course_id
                JOIN users u ON u.user_id = o.professor_id
                WHERE o.semester = ?
                ORDER BY o.course_id, o.section
                """;
        String timeSql = """
                SELECT t.offering_id, t.day_of_week, t.start_time, t.end_time
                FROM offering_time t
                JOIN course_offering o ON o.offering_id = t.offering_id
                WHERE o.semester = ?
                ORDER BY t.offering_id,
                         array_position(ARRAY['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']::VARCHAR[], t.day_of_week),
                         t.start_time
                """;
        String prerequisiteSql = """
                SELECT o.offering_id, p.prerequisite_course_id
                FROM course_prerequisite p
                JOIN course_offering o ON o.course_id = p.course_id
                WHERE o.semester = ?
                ORDER BY o.offering_id, p.seq
                """;
        Map<Integer, Offering> offerings = new LinkedHashMap<Integer, Offering>();

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(offeringSql)) {
                statement.setString(1, semester);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Offering offering = new Offering(resultSet.getInt("offering_id"),
                                resultSet.getString("course_id"),
                                resultSet.getString("course_name"),
                                resultSet.getInt("credits"),
                                resultSet.getString("semester"),
                                resultSet.getString("section"),
                                resultSet.getString("professor_id"),
                                resultSet.getString("professor_name"),
                                resultSet.getString("room"),
                                resultSet.getInt("capacity"),
                                resultSet.getInt("enrolled_count"),
                                new ArrayList<TimeSlot>(),
                                new ArrayList<String>());
                        offerings.put(offering.offeringId(), offering);
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(timeSql)) {
                statement.setString(1, semester);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Offering offering = offerings.get(resultSet.getInt("offering_id"));
                        if (offering != null) {
                            offering.times().add(new TimeSlot(resultSet.getString("day_of_week"),
                                    resultSet.getTime("start_time").toLocalTime(),
                                    resultSet.getTime("end_time").toLocalTime()));
                        }
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(prerequisiteSql)) {
                statement.setString(1, semester);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Offering offering = offerings.get(resultSet.getInt("offering_id"));
                        if (offering != null) {
                            offering.prerequisiteCourseIds().add(resultSet.getString("prerequisite_course_id"));
                        }
                    }
                }
            }
        }
        return offerings;
    }

    public Set<String> findCompletedCourseIds(String studentId) throws SQLException {
        String sql = """
                SELECT DISTINCT o.course_id
                FROM enrollment e
                JOIN course_offering o ON o.offering_id = e.offering_id
                WHERE e.student_id = ? AND e.status = 'COMPLETED'
                """;
        Set<String> courseIds = new HashSet<String>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    courseIds.add(resultSet.getString("course_id"));
                }
            }
        }
        return courseIds;
    }

    public int findMaxCredits(String studentId) throws SQLException {
        String sql = "SELECT max_credits FROM student WHERE student_id = ?";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("max_credits") : 0;
            }
        }
    }
}
