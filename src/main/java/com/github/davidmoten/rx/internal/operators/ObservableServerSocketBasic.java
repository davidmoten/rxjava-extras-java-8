package com.github.davidmoten.rx.internal.operators;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import com.github.davidmoten.rx.Bytes;
import com.github.davidmoten.rx.Checked;

import rx.Observable;
import rx.Observer;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.observables.SyncOnSubscribe;

public final class ObservableServerSocketBasic {

    private ObservableServerSocketBasic() {
        // prevent instantiation
    }

    public static Observable<Observable<byte[]>> create(final int port, final long timeout,
            final TimeUnit unit, final int bufferSize) {
        Func0<ServerSocket> serverSocketFactory = createServerSocketFactory(port);
        Func1<ServerSocket, Observable<Observable<byte[]>>> serverSocketObservable = serverSocket -> createServerSocketObservable(
                serverSocket, unit.toMillis(timeout), bufferSize);
        // Observable.using handles closing of stuff on termination or
        // unsubscription
        return Observable.using(serverSocketFactory, serverSocketObservable, closer(), true);
    }

    private static Observable<Observable<byte[]>> createServerSocketObservable(
            ServerSocket serverSocket, long timeoutMs, int bufferSize) {
        return Observable.create(new SyncOnSubscribe<ServerSocket, Observable<byte[]>>() {

            @Override
            protected ServerSocket generateState() {
                try {
                    serverSocket.setSoTimeout((int) timeoutMs);
                } catch (SocketException e) {
                    throw new RuntimeException(e);
                }
                return serverSocket;
            }

            @Override
            protected ServerSocket next(ServerSocket serverSocket,
                    Observer<? super Observable<byte[]>> observer) {
                Socket socket;
                while (true) {
                    try {
                        socket = serverSocket.accept();
                        System.out.println("accepted socket " + socket);
                        observer.onNext(createSocketObservable(socket, timeoutMs, bufferSize));
                        break;
                    } catch (SocketTimeoutException e) {
                        // timed out so will continue waiting
                    } catch (IOException e) {
                        // unknown problem
                        observer.onError(e);
                    }
                }
                return serverSocket;
            }
        });
    }

    private static Observable<byte[]> createSocketObservable(Socket socket, long timeoutMs,
            int bufferSize) {
        try {
            socket.setSoTimeout((int) timeoutMs);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        return Observable.using( //
                Checked.f0(() -> socket.getInputStream()), //
                is -> Bytes.from(is, bufferSize), //
                closer());
    }

    private static Func0<ServerSocket> createServerSocketFactory(int port) {
        return Checked.f0(() -> new ServerSocket(port));
    }

    // Visible for testing
    static Action1<Closeable> closer() {
        return c -> {
            try {
                c.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

}
