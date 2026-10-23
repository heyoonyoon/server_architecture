package com.serverarchitecture.academic;

import com.serverarchitecture.AcademicServiceGrpc;
import com.serverarchitecture.AccessLogInterceptor;
import com.serverarchitecture.AuthInterceptor;
import com.serverarchitecture.DataClient;
import com.serverarchitecture.GrpcServerRunner;
import com.serverarchitecture.JwtProvider;
import com.serverarchitecture.Role;
import com.serverarchitecture.ServiceConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class AcademicServer {
    static final Map<String, Set<Role>> PERMISSIONS = Map.ofEntries(
            Map.entry(AcademicServiceGrpc.getListStudentsMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(AcademicServiceGrpc.getListCoursesMethod().getFullMethodName(), Set.of(Role.PROFESSOR, Role.STUDENT)),
            Map.entry(AcademicServiceGrpc.getGetStudentMethod().getFullMethodName(), Set.of(Role.PROFESSOR, Role.SERVICE)),
            Map.entry(AcademicServiceGrpc.getAddStudentMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(AcademicServiceGrpc.getDeleteStudentMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(AcademicServiceGrpc.getAddCourseMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(AcademicServiceGrpc.getDeleteCourseMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(AcademicServiceGrpc.getListCoursesByStudentMethod().getFullMethodName(), Set.of(Role.PROFESSOR, Role.STUDENT)),
            Map.entry(AcademicServiceGrpc.getListStudentsByCourseMethod().getFullMethodName(), Set.of(Role.PROFESSOR)),
            Map.entry(AcademicServiceGrpc.getListCompletedCoursesMethod().getFullMethodName(), Set.of(Role.PROFESSOR, Role.STUDENT, Role.SERVICE)),
            Map.entry(AcademicServiceGrpc.getGetCatalogMethod().getFullMethodName(), Set.of(Role.PROFESSOR, Role.STUDENT, Role.SERVICE)));

    public static void main(String[] args) throws Exception {
        JwtProvider jwtProvider = JwtProvider.fromEnv();
        DataClient data = DataClient.fromEnv();
        AcademicServiceImpl service = new AcademicServiceImpl(
                new StudentRepository(data),
                new CourseRepository(data),
                new OfferingRepository(data));

        GrpcServerRunner.run("academic", ServiceConfig.ACADEMIC_PORT, service,
                List.of(new AuthInterceptor(jwtProvider, PERMISSIONS), new AccessLogInterceptor(jwtProvider)),
                data);
    }
}
