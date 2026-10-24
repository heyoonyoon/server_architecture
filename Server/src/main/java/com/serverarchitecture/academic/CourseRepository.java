package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.data.api.Item;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CourseRepository {
    private final DataClient data;

    public CourseRepository(DataClient data) {
        this.data = data;
    }

    public List<Course> findAll() {
        return data.readAll(DataCollections.COURSES).stream().map(CourseRepository::toCourse).toList();
    }

    public Optional<Course> findById(String courseId) {
        return data.read(DataCollections.COURSES, courseId).map(CourseRepository::toCourse);
    }

    public void create(Course course) {
        data.create(DataCollections.COURSES, DataClient.item(course.getCourseId(), Map.of(
                "name", course.getName(),
                "instructor_name", course.getInstructorName(),
                "credits", String.valueOf(course.getCredits()),
                "prerequisite_course_ids", Lists.join(course.getPrerequisiteCourses()))));
    }

    public boolean delete(String courseId) {
        return data.delete(DataCollections.COURSES, courseId);
    }

    private static Course toCourse(Item item) {
        Map<String, String> fields = item.getFieldsMap();
        return new Course(item.getId(),
                fields.getOrDefault("name", ""),
                fields.getOrDefault("instructor_name", ""),
                Integer.parseInt(fields.getOrDefault("credits", "3")),
                Lists.split(fields.get("prerequisite_course_ids")));
    }
}
