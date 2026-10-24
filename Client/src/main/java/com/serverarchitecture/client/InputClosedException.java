package com.serverarchitecture.client;

public class InputClosedException extends RuntimeException {
    public InputClosedException() {
        super("입력이 끝났습니다.");
    }
}
