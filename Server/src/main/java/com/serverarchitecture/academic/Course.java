package com.serverarchitecture.academic;

import java.util.List;

public class Course {
    protected String courseId;
    protected String name;
    protected String instructorName;
    protected int credits;
    protected List<String> prerequisiteCoursesList;

    public Course(String courseId, String name, String instructorName, int credits,
                  List<String> prerequisiteCoursesList) {
        this.courseId = courseId;
        this.name = name;
        this.instructorName = instructorName;
        this.credits = credits;
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

    public int getCredits() {
        return this.credits;
    }

    public List<String> getPrerequisiteCourses() {
        return this.prerequisiteCoursesList;
    }
}
