CREATE TABLE department (
    department_id VARCHAR(20) PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

INSERT INTO department (department_id, name) VALUES
    ('CS', '컴퓨터공학과'),
    ('EE', '전자공학과'),
    ('ME', '기계공학과');

INSERT INTO department (department_id, name)
SELECT DISTINCT department, department
FROM student
ON CONFLICT (department_id) DO NOTHING;

CREATE TABLE student_department (
    student_id VARCHAR(20) NOT NULL REFERENCES student (student_id) ON DELETE CASCADE,
    department_id VARCHAR(20) NOT NULL REFERENCES department (department_id),
    major_type VARCHAR(20) NOT NULL CHECK (major_type IN ('MAJOR', 'DOUBLE_MAJOR', 'MINOR')),
    PRIMARY KEY (student_id, department_id)
);

INSERT INTO student_department (student_id, department_id, major_type)
SELECT student_id, department, 'MAJOR'
FROM student;

ALTER TABLE student
    DROP COLUMN department,
    ADD COLUMN max_credits INT NOT NULL DEFAULT 18;

ALTER TABLE course
    ADD COLUMN credits INT NOT NULL DEFAULT 3;

CREATE TABLE course_offering (
    offering_id SERIAL PRIMARY KEY,
    course_id VARCHAR(20) NOT NULL REFERENCES course (course_id) ON DELETE CASCADE,
    semester VARCHAR(10) NOT NULL,
    section VARCHAR(5) NOT NULL,
    professor_id VARCHAR(20) NOT NULL REFERENCES professor (professor_id),
    capacity INT NOT NULL CHECK (capacity > 0),
    room VARCHAR(30),
    UNIQUE (course_id, semester, section)
);

CREATE TABLE offering_time (
    offering_id INT NOT NULL REFERENCES course_offering (offering_id) ON DELETE CASCADE,
    day_of_week VARCHAR(3) NOT NULL CHECK (day_of_week IN ('MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN')),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    CHECK (start_time < end_time)
);

CREATE TABLE enrollment (
    student_id VARCHAR(20) NOT NULL REFERENCES student (student_id) ON DELETE CASCADE,
    offering_id INT NOT NULL REFERENCES course_offering (offering_id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ENROLLED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'WITHDRAWN', 'CANCELLED')),
    grade VARCHAR(2),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, offering_id),
    CHECK ((status IN ('COMPLETED', 'FAILED')) = (grade IS NOT NULL))
);

CREATE INDEX enrollment_offering_status_idx ON enrollment (offering_id, status);

CREATE TEMPORARY TABLE instructor_professor (
    instructor_name VARCHAR(100) PRIMARY KEY,
    professor_id VARCHAR(20) NOT NULL
) ON COMMIT DROP;

INSERT INTO instructor_professor (instructor_name, professor_id) VALUES
    ('Park', 'P001'),
    ('Kim', 'P002'),
    ('Ko', 'P003'),
    ('Ahn', 'P004'),
    ('Lee', 'P005');

INSERT INTO course_offering (course_id, semester, section, professor_id, capacity)
SELECT c.course_id, '2026-1', '01', m.professor_id, 100
FROM course c
JOIN instructor_professor m ON m.instructor_name = c.instructor_name
ORDER BY c.course_id;

INSERT INTO enrollment (student_id, offering_id, status, grade, applied_at)
SELECT sc.student_id, o.offering_id, 'COMPLETED', 'P', TIMESTAMPTZ '2026-03-02 00:00:00+09'
FROM student_completed_course sc
JOIN course_offering o ON o.course_id = sc.course_id AND o.semester = '2026-1';

DROP TABLE student_completed_course;

CREATE TEMPORARY TABLE current_offering (
    course_id VARCHAR(20) NOT NULL,
    section VARCHAR(5) NOT NULL,
    professor_id VARCHAR(20),
    capacity INT NOT NULL,
    room VARCHAR(30) NOT NULL,
    PRIMARY KEY (course_id, section)
) ON COMMIT DROP;

INSERT INTO current_offering (course_id, section, professor_id, capacity, room) VALUES
    ('12345', '01', NULL, 30, 'Y5401'),
    ('23456', '01', NULL, 30, 'Y5402'),
    ('17651', '01', NULL, 30, 'Y5403'),
    ('17652', '01', NULL, 30, 'Y5404'),
    ('17653', '01', NULL, 30, 'Y5405'),
    ('17654', '01', NULL, 30, 'Y5406'),
    ('17655', '01', NULL, 30, 'Y5407'),
    ('17653', '02', 'P002', 5, 'Y5408');

INSERT INTO course_offering (course_id, semester, section, professor_id, capacity, room)
SELECT co.course_id, '2026-2', co.section, COALESCE(co.professor_id, m.professor_id), co.capacity, co.room
FROM current_offering co
JOIN course c ON c.course_id = co.course_id
JOIN instructor_professor m ON m.instructor_name = c.instructor_name
ORDER BY co.course_id, co.section;

INSERT INTO offering_time (offering_id, day_of_week, start_time, end_time)
SELECT o.offering_id, t.day_of_week, t.start_time, t.end_time
FROM (VALUES
    ('12345', '01', 'MON', TIME '09:00', TIME '10:30'),
    ('12345', '01', 'WED', TIME '09:00', TIME '10:30'),
    ('23456', '01', 'MON', TIME '10:30', TIME '12:00'),
    ('23456', '01', 'WED', TIME '10:30', TIME '12:00'),
    ('17651', '01', 'TUE', TIME '09:00', TIME '10:30'),
    ('17651', '01', 'THU', TIME '09:00', TIME '10:30'),
    ('17652', '01', 'TUE', TIME '09:00', TIME '10:30'),
    ('17652', '01', 'THU', TIME '13:00', TIME '14:30'),
    ('17653', '01', 'MON', TIME '13:00', TIME '14:30'),
    ('17654', '01', 'FRI', TIME '09:00', TIME '12:00'),
    ('17655', '01', 'WED', TIME '09:00', TIME '10:30'),
    ('17653', '02', 'TUE', TIME '15:00', TIME '16:30')
) AS t (course_id, section, day_of_week, start_time, end_time)
JOIN course_offering o ON o.course_id = t.course_id AND o.section = t.section AND o.semester = '2026-2';
