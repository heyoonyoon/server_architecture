package com.serverarchitecture;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

public class EnrollmentList {
    protected ArrayList<Enrollment> vEnrollment;

    public EnrollmentList(String sEnrollmentFileName) throws FileNotFoundException, IOException {
        this.vEnrollment = new ArrayList<Enrollment>();
        try (BufferedReader objEnrollmentFile = new BufferedReader(new FileReader(sEnrollmentFileName))) {
            String enrollmentInfo;
            while ((enrollmentInfo = objEnrollmentFile.readLine()) != null) {
                if (!enrollmentInfo.trim().equals("")) {
                    this.vEnrollment.add(new Enrollment(enrollmentInfo));
                }
            }
        }
    }

    public ArrayList<Enrollment> getAllEnrollmentRecords() {
        return this.vEnrollment;
    }

    public boolean isRegisteredEnrollment(String sSID, String sCID) {
        for (int i = 0; i < this.vEnrollment.size(); i++) {
            Enrollment objEnrollment = this.vEnrollment.get(i);
            if (objEnrollment.match(sSID, sCID)) {
                return true;
            }
        }
        return false;
    }
}
