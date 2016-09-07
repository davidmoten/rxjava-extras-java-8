package com.github.davidmoten.rx;

import java.nio.channels.AsynchronousChannelGroup;
import java.util.concurrent.TimeUnit;

import com.github.davidmoten.rx.internal.operators.ObservableServerSocket;

import rx.AsyncEmitter.BackpressureMode;
import rx.Observable;
import rx.functions.Func0;

public final class IO {

    private IO() {
        // prevent instantiation
    }

    public static Observable<Observable<byte[]>> serverSocket(int port, long timeout, TimeUnit unit,
            int bufferSize, BackpressureMode backpressureMode,
            Func0<AsynchronousChannelGroup> group) {
        return ObservableServerSocket.create(port, timeout, unit, bufferSize, backpressureMode,
                group);
    }

    public static Observable<Observable<byte[]>> serverSocket(int port, long timeout, TimeUnit unit,
            int bufferSize, BackpressureMode backpressureMode) {
        return serverSocket(port, timeout, unit, bufferSize, backpressureMode, null);
    }

}