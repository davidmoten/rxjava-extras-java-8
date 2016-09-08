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
import java.util.concurrent.Future;
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
        return Observable.using(serverSocketFactory, serverSocketObservable, closer(), true);
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
        }

        private final AtomicReference<State> state = new AtomicReference<State>(new State(true, 0));

        @Override
        public void request(long n) {
            if (n < 0) {
                throw new IllegalArgumentException("cannot request < 0");
            } else if (n == 0) {
                return;
            }

            // The way to initiate the processing of socket connections is to
            // call `serverSocketChannel.accept` once, then the accepted pattern
            // is to call `serverSocketChannel.accept` again in the
            // `completed()` method of the `CompletionHandler`. Calling `accept`
            // outside of this pattern risks provoking an
            // `AcceptPendingException`. However, if there are insufficient
            // requests of the parent the `completed()` method will not call
            // `accept` and the responsibility for calling `accept` falls to the
            // `request` method (just like on startup).

            // use CAS loop to safely update state
            while (true) {
                State s = state.get();
                long r = s.requested + n;
                // check for overflow
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
            // if accept happens here then set `canAcceptFromRequest` in state
            // to false otherwise set it to true
            while (true) {
                State s = state.get();
                long r = s.requested;
                boolean accept = r > 0;
                final State s2;
                if (accept) {
                    s2 = new State(false, decrement(r));
                } else {
                    s2 = new State(true, r);
                }
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

            MyEmitter emitter = new MyEmitter(socketChannel, bufferSize, timeoutMs);

            Observable<byte[]> obs = Observable.fromEmitter(emitter, backpressureMode);

            if (!subscriber.isUnsubscribed()) {
                // note to get here there must have been a request
                subscriber.onNext(obs);
            }
        }

        @Override
        public void failed(Throwable e, Void attachment) {
            if (!subscriber.isUnsubscribed()) {
                // note to get here there must have been a request
                subscriber.onNext(Observable.error(e));
            }
        }

    }

    private static final class MyEmitter implements Action1<AsyncEmitter<byte[]>> {

        private final AsynchronousSocketChannel socketChannel;
        private final int bufferSize;
        private final long timeoutMs;

        private volatile boolean done;
        private volatile Future<Integer> read;

        MyEmitter(AsynchronousSocketChannel socketChannel, int bufferSize, long timeoutMs) {
            this.socketChannel = socketChannel;
            this.bufferSize = bufferSize;
            this.timeoutMs = timeoutMs;
        }

        @Override
        public void call(AsyncEmitter<byte[]> emitter) {
            emitter.setCancellation(() -> {
                done = true;
                // pull the plug on a blocking read by cancelling the associated
                // future
                read.cancel(true);
            });

            // Allocate a byte buffer to read from the client
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            try {
                int bytesRead;
                while (!done
                        && (bytesRead = read(buffer).get(timeoutMs, TimeUnit.MILLISECONDS)) != -1) {
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
                if (!done) {
                    emitter.onError(e);
                }
            }

        }

        private Future<Integer> read(ByteBuffer buffer) {
            Future<Integer> future = socketChannel.read(buffer);
            this.read = future;
            // protect against race condition:
            // if just before `socketChannel.read` the emitter gets cancelled
            // (via unsubscribe) then we need to make sure that this future gets
            // cancelled too (we would otherwise have to wait for timeout)
            if (done) {
                future.cancel(true);
            }
            return future;
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
