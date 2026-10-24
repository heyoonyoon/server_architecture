package com.serverarchitecture.client;

import com.serverarchitecture.CancelEnrollmentRequest;
import com.serverarchitecture.CancelEnrollmentResponse;
import com.serverarchitecture.EnrollRequest;
import com.serverarchitecture.EnrollResponse;
import com.serverarchitecture.EnrollmentServiceGrpc;
import com.serverarchitecture.EnterQueueRequest;
import com.serverarchitecture.GetQueueStatusRequest;
import com.serverarchitecture.LeaveQueueRequest;
import com.serverarchitecture.ListMyEnrollmentsRequest;
import com.serverarchitecture.ListMyEnrollmentsResponse;
import com.serverarchitecture.ListOfferingsRequest;
import com.serverarchitecture.OfferingInfo;
import com.serverarchitecture.QueueState;
import com.serverarchitecture.QueueStatusResponse;
import com.serverarchitecture.WatchQueueRequest;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class EnrollmentClient {
    private final EnrollmentServiceGrpc.EnrollmentServiceBlockingStub stub;
    private volatile boolean inQueue;

    public EnrollmentClient(Channel channel, CallCredentials credentials) {
        this.stub = EnrollmentServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials);
    }

    private EnrollmentServiceGrpc.EnrollmentServiceBlockingStub call() {
        return stub.withDeadlineAfter(AcademicClient.DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    public QueueStatusResponse getQueueStatus() {
        return track(call().getQueueStatus(GetQueueStatusRequest.newBuilder().build()));
    }

    public QueueStatusResponse enterQueue() {
        return track(call().enterQueue(EnterQueueRequest.newBuilder().build()));
    }

    public Iterator<QueueStatusResponse> watchQueue() {
        return stub.watchQueue(WatchQueueRequest.newBuilder().build());
    }

    public List<OfferingInfo> listOfferings() {
        return call().listOfferings(ListOfferingsRequest.newBuilder().build()).getOfferingsList();
    }

    public EnrollResponse enroll(int offeringId) {
        return call().enroll(EnrollRequest.newBuilder().setOfferingId(offeringId).build());
    }

    public CancelEnrollmentResponse cancel(int offeringId) {
        return call().cancelEnrollment(CancelEnrollmentRequest.newBuilder().setOfferingId(offeringId).build());
    }

    public ListMyEnrollmentsResponse listMyEnrollments() {
        return call().listMyEnrollments(ListMyEnrollmentsRequest.newBuilder().build());
    }

    public void leaveQueue() {
        if (!inQueue) {
            return;
        }
        inQueue = false;
        try {
            stub.withDeadlineAfter(2, TimeUnit.SECONDS).leaveQueue(LeaveQueueRequest.newBuilder().build());
        } catch (StatusRuntimeException ignored) {
        }
    }

    private QueueStatusResponse track(QueueStatusResponse status) {
        inQueue = status.getState() == QueueState.WAITING || status.getState() == QueueState.ADMITTED;
        return status;
    }
}
