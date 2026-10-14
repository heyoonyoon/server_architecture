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

        view.setData(stub);

    }

}
