BEGIN;

DELETE FROM course WHERE course_id LIKE 'LT%';

DELETE FROM users WHERE role = 'STUDENT' AND user_id BETWEEN '30000001' AND '30003660';

DELETE FROM department WHERE department_id LIKE 'LT-%';

COMMIT;

SELECT
    (SELECT count(*) FROM users WHERE user_id BETWEEN '30000001' AND '30003660') AS loadtest_students,
    (SELECT count(*) FROM course_offering WHERE course_id LIKE 'LT%') AS loadtest_offerings,
    (SELECT count(*) FROM department WHERE department_id LIKE 'LT-%') AS loadtest_departments;
