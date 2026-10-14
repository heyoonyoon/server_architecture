CREATE TABLE student (
    student_id VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(50) NOT NULL
);

CREATE TABLE student_completed_course (
    student_id VARCHAR(20) NOT NULL REFERENCES student (student_id) ON DELETE CASCADE,
    course_id VARCHAR(20) NOT NULL,
    seq INT NOT NULL,
    PRIMARY KEY (student_id, course_id)
);

CREATE TABLE course (
    course_id VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    instructor_name VARCHAR(100) NOT NULL
);

CREATE TABLE course_prerequisite (
    course_id VARCHAR(20) NOT NULL REFERENCES course (course_id) ON DELETE CASCADE,
    prerequisite_course_id VARCHAR(20) NOT NULL,
    seq INT NOT NULL,
    PRIMARY KEY (course_id, prerequisite_course_id)
);
