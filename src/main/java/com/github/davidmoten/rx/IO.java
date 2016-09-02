package com.github.davidmoten.rx;

import java.util.concurrent.TimeUnit;

import com.github.davidmoten.rx.internal.operators.ObservableServerSocket;

import rx.Observable;

public final class IO {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private IO() {
        // prevent instantiation
    }

    public static Observable<ConnectionNotification> serverSocket(int port, long timeout,
            TimeUnit unit, int bufferSize) {
        return ObservableServerSocket.create(port, timeout, unit, bufferSize);
    }

    public static Observable<ConnectionNotification> serverSocket(int port, long timeout,
            TimeUnit unit) {
        return serverSocket(port, timeout, unit, DEFAULT_BUFFER_SIZE);
    }

}