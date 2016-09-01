package com.github.davidmoten.rx.internal.operators;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.github.davidmoten.rx.ConnectionNotification;

import rx.AsyncEmitter;
import rx.AsyncEmitter.BackpressureMode;
import rx.AsyncEmitter.Cancellable;
import rx.Notification;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public final class SourceServerSocket {

    private SourceServerSocket() {
        // prevent instantiation
    }

    public static Observable<ConnectionNotification> create(final int port, final long timeout,
            final TimeUnit unit, final int bufferSize) {

        Func0<AsynchronousServerSocketChannel> serverSocketCreator = new Func0<AsynchronousServerSocketChannel>() {

            @Override
            public AsynchronousServerSocketChannel call() {
                try {
                    return AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(port));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        Func1<AsynchronousServerSocketChannel, Observable<ConnectionNotification>> serverSocketObservable = new Func1<AsynchronousServerSocketChannel, Observable<ConnectionNotification>>() {

            @Override
            public Observable<ConnectionNotification> call(
                    final AsynchronousServerSocketChannel channel) {
                Action1<AsyncEmitter<ConnectionNotification>> emitterAction = new Action1<AsyncEmitter<ConnectionNotification>>() {

                    @Override
                    public void call(AsyncEmitter<ConnectionNotification> emitter) {
                        channel.accept(null,
                                new Handler(channel, emitter, unit.toMillis(timeout), bufferSize));

                    }
                };
                return Observable.fromAsync(emitterAction, BackpressureMode.BUFFER);
            }
        };
        Action1<Closeable> serverSocketDisposer = closer();
        return Observable.using(serverSocketCreator, serverSocketObservable, serverSocketDisposer);
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

    private static final class Handler
            implements CompletionHandler<AsynchronousSocketChannel, Void> {

        private final AsynchronousServerSocketChannel serverSocketChannel;
        private final AsyncEmitter<ConnectionNotification> emitter;
        private final long timeoutMs;
        private final int bufferSize;

        private volatile boolean done = false;

        public Handler(AsynchronousServerSocketChannel serverSocketChannel,
                AsyncEmitter<ConnectionNotification> emitter, long timeoutMs, int bufferSize) {
            this.serverSocketChannel = serverSocketChannel;
            this.emitter = emitter;
            this.timeoutMs = timeoutMs;
            this.bufferSize = bufferSize;
            emitter.setCancellation(new Cancellable() {

                @Override
                public void cancel() throws Exception {
                    done = false;
                }
            });
        }

        @Override
        public void completed(AsynchronousSocketChannel socketChannel, Void attachment) {
            // listen for new connection
            serverSocketChannel.accept(null, this);
            // Allocate a byte buffer to read from the client
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            String id = UUID.randomUUID().toString();
            try {
                int bytesRead;
                while (!done && (bytesRead = socketChannel.read(buffer).get(timeoutMs,
                        TimeUnit.MILLISECONDS)) != -1) {
                    // check the value of done again because the read may have
                    // taken some time (close to the timeout)
                    if (done) {
                        return;
                    }

                    // Make the buffer ready to read
                    buffer.flip();

                    // copy the current buffer to a byte array
                    byte[] chunk = new byte[bytesRead];
                    buffer.get(chunk, 0, bytesRead);

                    // emit the chunk
                    emitter.onNext(
                            new ConnectionNotification(id, Notification.createOnNext(chunk)));

                    // Make the buffer ready to write
                    buffer.clear();
                }
                if (!done) {
                    emitter.onNext(new ConnectionNotification(id,
                            Notification.<byte[]> createOnCompleted()));
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                error(id, e);
            }
        }

        @Override
        public void failed(Throwable e, Void attachment) {
            String id = UUID.randomUUID().toString();
            error(id, e);
        }

        private void error(String id, Throwable e) {
            if (!done) {
                emitter.onNext(
                        new ConnectionNotification(id, Notification.<byte[]> createOnError(e)));
            }
        }

    }

}
