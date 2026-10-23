package com.serverarchitecture.academic;

import java.util.ArrayList;

public class Course {
    protected String courseId;
    protected String name;
    protected String instructorName;
    protected ArrayList<String> prerequisiteCoursesList;

    public Course(String courseId, String name, String instructorName, ArrayList<String> prerequisiteCoursesList) {
        this.courseId = courseId;
        this.name = name;
        this.instructorName = instructorName;
        this.prerequisiteCoursesList = prerequisiteCoursesList;
    }

    public String getCourseId() {
        return this.courseId;
    }

    public String getName() {
        return this.name;
    }

    public String getInstructorName() {
        return this.instructorName;
    }

    public ArrayList<String> getPrerequisiteCourses() {
        return this.prerequisiteCoursesList;
    }
}
