package com.serverarchitecture.client;

import com.serverarchitecture.LoginResponse;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConsoleClient {
    private final ManagedChannel channel;
    private final ConsoleView view;
    private final AuthClient auth;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile EnrollmentClient enrollment;

    public ConsoleClient(ManagedChannel channel, ConsoleView view) {
        this.channel = channel;
        this.view = view;
        this.auth = new AuthClient(channel);
    }

    public static void main(String[] args) {
        String host = env("SERVER_HOST", "localhost");
        int port = port(env("SERVER_PORT", "8080"));

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();

        ConsoleClient client = new ConsoleClient(channel, new ConsoleView(System.in, System.out));
        Runtime.getRuntime().addShutdownHook(new Thread(client::close));

        System.out.println("서버 " + host + ":" + port);
        client.run();
        client.close();
    }

    public void run() {
        view.println("===== 수강신청 시스템 =====");
        try {
            LoginResponse user = login();
            CallCredentials credentials = new TokenCredentials(user.getToken());
            AcademicClient academic = new AcademicClient(channel, credentials);

            switch (user.getRole()) {
                case STUDENT:
                    view.println(user.getName() + " 학생, 환영합니다.");
                    enrollment = new EnrollmentClient(channel, credentials);
                    new StudentMenu(view, academic, enrollment, user.getUserId()).run();
                    break;
                case PROFESSOR:
                    view.println(user.getName() + " 교수님, 환영합니다.");
                    new ProfessorMenu(view, academic).run();
                    break;
                default:
                    view.println("콘솔에서 사용할 수 없는 계정입니다.");
            }
        } catch (InputClosedException e) {
            view.println("");
            view.println("입력이 끝나 종료합니다.");
        }
    }

    private LoginResponse login() {
        while (true) {
            String userId = view.readRequired("아이디: ");
            if (userId == null) {
                continue;
            }
            String password = view.readLine("비밀번호: ");

            try {
                return auth.login(userId, password);
            } catch (StatusRuntimeException e) {
                view.printError(e);
            }
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        EnrollmentClient current = enrollment;
        if (current != null) {
            current.leaveQueue();
        }
        channel.shutdown();
        try {
            if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static int port(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.out.println("SERVER_PORT가 숫자가 아니어서 8080을 사용합니다.");
            return 8080;
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
