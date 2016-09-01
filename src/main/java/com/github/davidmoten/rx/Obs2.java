package com.github.davidmoten.rx;

import java.util.concurrent.TimeUnit;

import com.github.davidmoten.rx.internal.operators.SourceServerSocket;

import rx.Observable;

public final class Obs2 {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private Obs2() {
        // prevent instantiation
    }

    public static Observable<ConnectionNotification> serverSocket(int port, long timeout,
            TimeUnit unit, int bufferSize) {
        return SourceServerSocket.create(port, timeout, unit, bufferSize);
    }

    public static Observable<ConnectionNotification> serverSocket(int port, long timeout,
            TimeUnit unit) {
        return serverSocket(port, timeout, unit, DEFAULT_BUFFER_SIZE);
    }

}