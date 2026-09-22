package com.serverarchitecture;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

public class CourseList {
    protected ArrayList<Course> vCourse;

    public CourseList(String sCourseFileName) throws FileNotFoundException, IOException {
        this.vCourse = new ArrayList<Course>();
        try (BufferedReader objCourseFile = new BufferedReader(new FileReader(sCourseFileName))) {
            String courseInfo;
            while ((courseInfo = objCourseFile.readLine()) != null) {
                if (!courseInfo.trim().equals("")) {
                    this.vCourse.add(new Course(courseInfo));
                }
            }
        }
    }

    public ArrayList<Course> getAllCourseRecords() {
        return this.vCourse;
    }

    public boolean isRegisteredCourse(String sCID) {
        for (int i = 0; i < this.vCourse.size(); i++) {
            Course objCourse = this.vCourse.get(i);
            if (objCourse.match(sCID)) {
                return true;
            }
        }
        return false;
    }
}
