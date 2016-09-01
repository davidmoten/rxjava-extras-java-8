package com.github.davidmoten.rx;

import java.util.concurrent.TimeUnit;

import com.github.davidmoten.rx.internal.operators.SourceServerSocket;

import rx.Observable;

public final class Obs2 {

    private Obs2() {
        // prevent instantiation
    }

    public static Observable<ConnectionNotification> serverSocket(int port, long timeout,
            TimeUnit unit, int bufferSize) {
        return SourceServerSocket.create(port, timeout, unit, bufferSize);
    }

}