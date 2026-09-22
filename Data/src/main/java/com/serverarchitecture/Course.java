package com.serverarchitecture;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.StringTokenizer;

public class Course implements Serializable {
    private static final long serialVersionUID = 1L;
    protected String courseId;
    protected String name;
    protected String instructorName;
    protected ArrayList<String> prerequisiteCoursesList;

    public Course(String inputString) {
        StringTokenizer stringTokenizer = new StringTokenizer(inputString);
        this.courseId = stringTokenizer.nextToken();
        this.instructorName = stringTokenizer.nextToken();
        this.name = stringTokenizer.nextToken();
        this.prerequisiteCoursesList = new ArrayList<String>();
        while (stringTokenizer.hasMoreTokens()) {
            this.prerequisiteCoursesList.add(stringTokenizer.nextToken());
        }
    }

    public boolean match(String courseId) {
        return this.courseId.equals(courseId);
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

    public String toString() {
        String stringReturn = this.courseId + " " + this.instructorName + " " + this.name;
        for (int i = 0; i < this.prerequisiteCoursesList.size(); i++) {
            stringReturn = stringReturn + " " + this.prerequisiteCoursesList.get(i).toString();
        }
        return stringReturn;
    }
}
