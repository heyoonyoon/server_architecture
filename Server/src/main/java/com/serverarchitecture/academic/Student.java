package com.serverarchitecture.academic;

import java.util.List;

public class Student {
    protected String studentId;
    protected String name;
    protected String department;
    protected int maxCredits;
    protected List<String> completedCoursesList;

    public Student(String studentId, String name, String department, int maxCredits,
                   List<String> completedCoursesList) {
        this.studentId = studentId;
        this.name = name;
        this.department = department;
        this.maxCredits = maxCredits;
        this.completedCoursesList = completedCoursesList;
    }

    public String getStudentId() {
        return this.studentId;
    }

    public String getName() {
        return this.name;
    }

    public String getDepartment() {
        return this.department;
    }

    public int getMaxCredits() {
        return this.maxCredits;
    }

    public List<String> getCompletedCourses() {
        return this.completedCoursesList;
    }
}
