BEGIN;

INSERT INTO department (department_id, name) VALUES
    ('LT-HUM-KOR', '인문대학 국어국문학과'),
    ('LT-HUM-ENG', '인문대학 영어영문학과'),
    ('LT-HUM-HIS', '인문대학 사학과'),
    ('LT-HUM-PHI', '인문대학 철학과'),
    ('LT-HUM-CHN', '인문대학 중어중문학과'),
    ('LT-HUM-JPN', '인문대학 일어일문학과'),
    ('LT-SOC-PAD', '사회과학대학 행정학과'),
    ('LT-SOC-ECO', '사회과학대학 경제학과'),
    ('LT-SOC-POL', '사회과학대학 정치외교학과'),
    ('LT-SOC-MED', '사회과학대학 디지털미디어학과'),
    ('LT-SOC-CHD', '사회과학대학 아동학과'),
    ('LT-AI-CSE', '인공지능소프트웨어융합대학 컴퓨터공학과'),
    ('LT-AI-DAT', '인공지능소프트웨어융합대학 데이터테크놀로지학과'),
    ('LT-AI-SWE', '인공지능소프트웨어융합대학 융합소프트웨어학과')
ON CONFLICT (department_id) DO NOTHING;

CREATE TEMPORARY TABLE lt_student ON COMMIT DROP AS
SELECT
    (30000000 + n)::text AS student_id,
    n,
    CASE
        WHEN n <= 1748 THEN (ARRAY['LT-HUM-KOR', 'LT-HUM-ENG', 'LT-HUM-HIS', 'LT-HUM-PHI', 'LT-HUM-CHN', 'LT-HUM-JPN'])[n % 6 + 1]
        WHEN n <= 3095 THEN (ARRAY['LT-SOC-PAD', 'LT-SOC-ECO', 'LT-SOC-POL', 'LT-SOC-MED', 'LT-SOC-CHD'])[n % 5 + 1]
        ELSE (ARRAY['LT-AI-CSE', 'LT-AI-DAT', 'LT-AI-SWE'])[n % 3 + 1]
    END AS department_id
FROM generate_series(1, 3660) AS n;

INSERT INTO users (user_id, name, role, password_hash)
SELECT student_id, '0811학생' || lpad(n::text, 4, '0'), 'STUDENT',
       '$2a$10$WAve6itLcnCeGerAZ5lh2.r8p.n7mg.y5vvTifSAtHkw2m4XkIoXW'
FROM lt_student
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO student (student_id)
SELECT student_id FROM lt_student
ON CONFLICT (student_id) DO NOTHING;

INSERT INTO student_department (student_id, department_id, major_type)
SELECT student_id, department_id, 'MAJOR' FROM lt_student
ON CONFLICT (student_id, department_id) DO NOTHING;

INSERT INTO course (course_id, name, instructor_name, credits)
SELECT 'LT' || lpad(c::text, 3, '0'),
       (ARRAY['글쓰기', '영어회화', '한국사의이해', '철학과윤리', '경제학원론', '정치학개론', '미디어와사회', '데이터분석기초', '파이썬프로그래밍', '인공지능개론'])[(c - 1) / 6 + 1]
           || ' ' || ((c - 1) % 6 + 1) || '분반군',
       'Kim', 3
FROM generate_series(1, 60) AS c
ON CONFLICT (course_id) DO NOTHING;

INSERT INTO course_offering (course_id, semester, section, professor_id, capacity, room)
SELECT 'LT' || lpad(c::text, 3, '0'), '2026-2', lpad(s::text, 2, '0'),
       'P00' || ((c + s) % 5 + 1),
       20 + (c * 7 + s * 13) % 41,
       'LT' || lpad((c * 10 + s)::text, 4, '0')
FROM generate_series(1, 60) AS c, generate_series(1, 5) AS s
ON CONFLICT (course_id, semester, section) DO NOTHING;

INSERT INTO offering_time (offering_id, day_of_week, start_time, end_time)
SELECT o.offering_id, t.day_of_week, t.start_time, t.end_time
FROM course_offering o
JOIN (VALUES
    (0, 'MON', TIME '09:00', TIME '10:30'),
    (0, 'WED', TIME '09:00', TIME '10:30'),
    (1, 'MON', TIME '10:30', TIME '12:00'),
    (1, 'WED', TIME '10:30', TIME '12:00'),
    (2, 'TUE', TIME '09:00', TIME '10:30'),
    (2, 'THU', TIME '09:00', TIME '10:30'),
    (3, 'TUE', TIME '13:00', TIME '14:30'),
    (3, 'THU', TIME '13:00', TIME '14:30'),
    (4, 'FRI', TIME '09:00', TIME '12:00'),
    (5, 'MON', TIME '15:00', TIME '16:30'),
    (5, 'WED', TIME '15:00', TIME '16:30')
) AS t (slot_group, day_of_week, start_time, end_time)
    ON t.slot_group = (substring(o.course_id FROM 3)::int - 1) % 6
WHERE o.course_id LIKE 'LT%'
  AND o.semester = '2026-2'
  AND NOT EXISTS (SELECT 1 FROM offering_time x WHERE x.offering_id = o.offering_id);

COMMIT;

SELECT
    (SELECT count(*) FROM users WHERE user_id BETWEEN '30000001' AND '30003660') AS loadtest_students,
    (SELECT count(*) FROM course_offering WHERE course_id LIKE 'LT%' AND semester = '2026-2') AS loadtest_offerings,
    (SELECT sum(capacity) FROM course_offering WHERE course_id LIKE 'LT%' AND semester = '2026-2') AS loadtest_seats;

SELECT split_part(sd.department_id, '-', 2) AS college, count(*) AS students
FROM student_department sd
WHERE sd.department_id LIKE 'LT-%'
GROUP BY 1
ORDER BY 1;
