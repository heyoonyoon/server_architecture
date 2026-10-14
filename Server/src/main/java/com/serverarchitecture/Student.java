package com.serverarchitecture;

import java.util.ArrayList;

public class Student {
    protected String studentId;
    protected String name;
    protected String department;
    protected ArrayList<String> completedCoursesList;

    public Student(String studentId, String name, String department, ArrayList<String> completedCoursesList) {
        this.studentId = studentId;
        this.name = name;
        this.department = department;
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

    public ArrayList<String> getCompletedCourses() {
        return this.completedCoursesList;
    }
}
