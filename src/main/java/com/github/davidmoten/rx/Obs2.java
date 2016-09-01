package com.github.davidmoten.rx;

import java.util.concurrent.TimeUnit;

import com.github.davidmoten.rx.internal.operators.SourceServerSocket;

import rx.Observable;

public final class Obs2 {

    public static Observable<ConnectionNotification> serverSocket(int port, long timeout,
            TimeUnit unit) {
        return SourceServerSocket.create(port, timeout, unit);
    }

}