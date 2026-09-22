package com.serverarchitecture;

import java.io.Serializable;
import java.util.StringTokenizer;

public class Enrollment implements Serializable {
    private static final long serialVersionUID = 1L;
    protected String studentId;
    protected String courseId;

    public Enrollment(String inputString) {
        StringTokenizer stringTokenizer = new StringTokenizer(inputString);
        this.studentId = stringTokenizer.nextToken();
        this.courseId = stringTokenizer.nextToken();
    }

    public boolean match(String studentId, String courseId) {
        return this.studentId.equals(studentId) && this.courseId.equals(courseId);
    }

    public String getStudentId() {
        return this.studentId;
    }

    public String getCourseId() {
        return this.courseId;
    }

    public String toString() {
        return this.studentId + " " + this.courseId;
    }
}
