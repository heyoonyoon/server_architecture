package com.serverarchitecture.academic;

import com.serverarchitecture.DataClient;
import com.serverarchitecture.DataCollections;
import com.serverarchitecture.data.api.Item;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class StudentRepository {
    private final DataClient data;

    public StudentRepository(DataClient data) {
        this.data = data;
    }

    public List<Student> findAll() {
        return data.readAll(DataCollections.STUDENTS).stream().map(StudentRepository::toStudent).toList();
    }

    public Optional<Student> findById(String studentId) {
        return data.read(DataCollections.STUDENTS, studentId).map(StudentRepository::toStudent);
    }

    public void create(Student student) {
        data.create(DataCollections.STUDENTS, DataClient.item(student.getStudentId(), Map.of(
                "name", student.getName(),
                "department", student.getDepartment(),
                "max_credits", String.valueOf(student.getMaxCredits()),
                "completed_course_ids", Lists.join(student.getCompletedCourses()))));
    }

    public boolean delete(String studentId) {
        return data.delete(DataCollections.STUDENTS, studentId);
    }

    private static Student toStudent(Item item) {
        Map<String, String> fields = item.getFieldsMap();
        return new Student(item.getId(),
                fields.getOrDefault("name", ""),
                fields.getOrDefault("department", ""),
                Integer.parseInt(fields.getOrDefault("max_credits", "18")),
                Lists.split(fields.get("completed_course_ids")));
    }
}
