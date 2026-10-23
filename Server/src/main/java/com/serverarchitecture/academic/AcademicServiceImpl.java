package com.serverarchitecture.academic;

import com.serverarchitecture.AcademicServiceGrpc;

public class AcademicServiceImpl extends AcademicServiceGrpc.AcademicServiceImplBase {
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final OfferingRepository offeringRepository;

    public AcademicServiceImpl(StudentRepository studentRepository, CourseRepository courseRepository,
                               OfferingRepository offeringRepository) {
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.offeringRepository = offeringRepository;
    }
}
