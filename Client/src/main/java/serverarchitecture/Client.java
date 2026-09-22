package serverarchitecture;

import com.serverarchitecture.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;


public class Client {
    public static void main(String[] args) {

        ClientView view = new ClientView();

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 8081)
                .usePlaintext()
                .build();

        ServerClientServiceGrpc.ServerClientServiceBlockingStub stub
                = ServerClientServiceGrpc.newBlockingStub(channel);

        GetStudentListControllerRequest studentListRequest = GetStudentListControllerRequest.newBuilder()
                .build();

        GetCourseListControllerRequest courseListRequest = GetCourseListControllerRequest.newBuilder()
                .build();

        GetStudentListControllerResponse studentListResponse = stub.getStudentListController(studentListRequest);
        GetCourseListControllerResponse courseListResponse = stub.getCourseListController(courseListRequest);

        view.showView(studentListResponse, courseListResponse);

    }

}
