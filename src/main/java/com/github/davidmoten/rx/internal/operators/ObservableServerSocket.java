package com.github.davidmoten.rx.internal.operators;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import rx.AsyncEmitter;
import rx.AsyncEmitter.BackpressureMode;
import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Producer;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;

public final class ObservableServerSocket {

    private ObservableServerSocket() {
        // prevent instantiation
    }

    public static Observable<Observable<byte[]>> create(final int port, final long timeout,
            final TimeUnit unit, final int bufferSize, BackpressureMode backpressureMode,
            Func0<AsynchronousChannelGroup> group) {
        Func0<AsynchronousServerSocketChannel> serverSocketFactory = createServerSocketFactory(port,
                group);
        Func1<AsynchronousServerSocketChannel, Observable<Observable<byte[]>>> serverSocketObservable = serverSocketChannel -> Observable
                .create(new MyOnSubscribe(serverSocketChannel, unit.toMillis(timeout), bufferSize,
                        backpressureMode));
        // Observable.using handles closing of stuff on termination or
        // unsubscription
        return Observable.using(serverSocketFactory, serverSocketObservable, closer());
    }

    private static Func0<AsynchronousServerSocketChannel> createServerSocketFactory(final int port,
            Func0<AsynchronousChannelGroup> group) {
        return () -> {
            try {
                AsynchronousChannelGroup g = group == null ? null : group.call();
                return AsynchronousServerSocketChannel.open(g).bind(new InetSocketAddress(port));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static final class MyOnSubscribe implements OnSubscribe<Observable<byte[]>> {

        private final AsynchronousServerSocketChannel serverSocketChannel;
        private final long timeoutMs;
        private final int bufferSize;
        private final BackpressureMode backpressureMode;

        MyOnSubscribe(AsynchronousServerSocketChannel serverSocketChannel, long timeoutMs,
                int bufferSize, BackpressureMode backpressureMode) {
            this.serverSocketChannel = serverSocketChannel;
            this.timeoutMs = timeoutMs;
            this.bufferSize = bufferSize;
            this.backpressureMode = backpressureMode;
        }

        @Override
        public void call(Subscriber<? super Observable<byte[]>> subscriber) {
            subscriber.setProducer(new MyProducer(serverSocketChannel, timeoutMs, bufferSize,
                    subscriber, backpressureMode));
        }

    }

    private static final class MyProducer
            implements CompletionHandler<AsynchronousSocketChannel, Void>, Producer {

        private final AsynchronousServerSocketChannel serverSocketChannel;
        private final long timeoutMs;
        private final int bufferSize;
        private final Subscriber<? super Observable<byte[]>> subscriber;
        private final BackpressureMode backpressureMode;

        MyProducer(AsynchronousServerSocketChannel serverSocketChannel, long timeoutMs,
                int bufferSize, Subscriber<? super Observable<byte[]>> subscriber,
                BackpressureMode backpressureMode) {
            this.serverSocketChannel = serverSocketChannel;
            this.timeoutMs = timeoutMs;
            this.bufferSize = bufferSize;
            this.subscriber = subscriber;
            this.backpressureMode = backpressureMode;
        }

        private static final class State {
            final boolean canAcceptFromRequest;
            final long requested;

            State(boolean accepting, long requested) {
                this.canAcceptFromRequest = accepting;
                this.requested = requested;
            }

            static State create(boolean canAcceptFromRequest, long requested) {
                return new State(canAcceptFromRequest, requested);
            }

            @Override
            public String toString() {
                return "State [canAcceptFromRequest=" + canAcceptFromRequest + ", requested="
                        + requested + "]";
            }

        }

        private final AtomicReference<State> state = new AtomicReference<State>(new State(true, 0));

        @Override
        public void request(long n) {
            if (n <= 0)
                return;

            // use CAS loop to safely update state
            while (true) {
                State s = state.get();
                long r = s.requested + n;
                if (r < 0) {
                    r = Long.MAX_VALUE;
                }
                boolean accept = s.canAcceptFromRequest && r > 0;
                final State s2;
                if (accept) {
                    s2 = State.create(false, decrement(r));
                } else {
                    s2 = State.create(s.canAcceptFromRequest, r);
                }
                if (state.compareAndSet(s, s2)) {
                    if (accept) {
                        serverSocketChannel.accept(null, this);
                    }
                    break;
                }
            }
        }

        private void checkRequests() {
            // use CAS loop to safely update state
            while (true) {
                State s = state.get();
                long r = s.requested;
                boolean accept = r > 0;
                final State s2;
                if (accept)
                    s2 = new State(false, decrement(r));
                else
                    s2 = new State(true, r);
                if (state.compareAndSet(s, s2)) {
                    if (accept) {
                        serverSocketChannel.accept(null, this);
                    }
                    break;
                }
            }
        }

        private static long decrement(long r) {
            if (r == Long.MAX_VALUE) {
                return r;
            } else {
                return r - 1;
            }
        }

        @Override
        public void completed(AsynchronousSocketChannel socketChannel, Void attachment) {

            checkRequests();

            Action1<AsyncEmitter<byte[]>> emitterAction = new Action1<AsyncEmitter<byte[]>>() {

                volatile boolean done;

                @Override
                public void call(AsyncEmitter<byte[]> emitter) {
                    emitter.setCancellation(() -> {
                        done = true;
                        // pull the plug on a blocking read
                        socketChannel.close();
                    });

                    // Allocate a byte buffer to read from the client
                    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
                    try {
                        int bytesRead;
                        while (!done && (bytesRead = socketChannel.read(buffer).get(timeoutMs,
                                TimeUnit.MILLISECONDS)) != -1) {
                            // check the value of done again because the read
                            // may have taken some time (waiting for timeout)
                            if (done) {
                                return;
                            }

                            // Make the buffer ready to read
                            buffer.flip();

                            // copy the current buffer to a byte array
                            byte[] chunk = new byte[bytesRead];
                            buffer.get(chunk, 0, bytesRead);

                            // emit the chunk
                            emitter.onNext(chunk);

                            // Make the buffer ready to write
                            buffer.clear();
                        }
                        if (!done) {
                            emitter.onCompleted();
                        }
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        emitter.onError(e);
                    }

                }
            };

            Observable<byte[]> obs = Observable.fromEmitter(emitterAction, backpressureMode);
            if (!subscriber.isUnsubscribed()) {
                subscriber.onNext(obs);
            }
        }

        @Override
        public void failed(Throwable e, Void attachment) {
            if (!subscriber.isUnsubscribed()) {
                subscriber.onNext(Observable.error(e));
            }
        }

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
