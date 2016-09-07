package com.github.davidmoten.rx.internal.operators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

import com.github.davidmoten.junit.Asserts;
import com.github.davidmoten.rx.Actions;
import com.github.davidmoten.rx.Bytes;
import com.github.davidmoten.rx.Checked;
import com.github.davidmoten.rx.IO;

import rx.AsyncEmitter.BackpressureMode;
import rx.Observable;
import rx.Producer;
import rx.Scheduler;
import rx.Subscriber;
import rx.functions.Func0;
import rx.observers.TestSubscriber;
import rx.schedulers.Schedulers;

public final class ObservableServerSocketTest {

    private static final int PORT = 12345;
    private static final String TEXT = "hello there";

    @Test
    public void serverSocketReadsTcpPushWhenBufferIsSmallerThanInput()
            throws UnknownHostException, IOException, InterruptedException {
        checkServerSocketReadsTcpPushWhenBufferSizeIs(TEXT, 4);
    }

    @Test
    public void serverSocketReadsTcpPushWhenBufferIsBiggerThanInput()
            throws UnknownHostException, IOException, InterruptedException {
        checkServerSocketReadsTcpPushWhenBufferSizeIs(TEXT, 8192);
    }

    @Test
    public void serverSocketReadsTcpPushWhenBufferIsSameSizeAsInput()
            throws UnknownHostException, IOException, InterruptedException {
        checkServerSocketReadsTcpPushWhenBufferSizeIs(TEXT, TEXT.length());
    }

    @Test
    public void serverSocketReadsTcpPushWhenInputIsEmpty()
            throws UnknownHostException, IOException, InterruptedException {
        checkServerSocketReadsTcpPushWhenBufferSizeIs("", 4);
    }

    @Test
    public void serverSocketReadsTcpPushWhenInputIsOneCharacter()
            throws UnknownHostException, IOException, InterruptedException {
        checkServerSocketReadsTcpPushWhenBufferSizeIs("a", 4);
    }

    @Test
    public void errorEmittedIfServerSocketBusy() throws IOException {

        TestSubscriber<Object> ts = TestSubscriber.create();
        try (ServerSocket socket = new ServerSocket(PORT)) {
            IO.serverSocket(PORT, 10, TimeUnit.SECONDS, 5, BackpressureMode.BUFFER).subscribe(ts);
            ts.assertNoValues();
            ts.assertNotCompleted();
            ts.assertTerminalEvent();
            assertTrue(ts.getOnErrorEvents().get(0).getCause() instanceof BindException);
        }
    }

    @Test
    public void negativeRequestEmitsError() throws IOException {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        Subscriber<Object> s = new Subscriber<Object>() {

            @Override
            public void onStart() {
                request(0);
            }

            @Override
            public void setProducer(Producer p) {
                p.request(0);
                p.request(-1);
            }

            @Override
            public void onCompleted() {

            }

            @Override
            public void onError(Throwable e) {
                exception.set(e);
            }

            @Override
            public void onNext(Object t) {

            }
        };
        try {
            IO.serverSocket(PORT, 10, TimeUnit.SECONDS, 5, BackpressureMode.BUFFER)
                    .unsafeSubscribe(s);
            Throwable ex = exception.get();
            assertNotNull(ex);
            assertTrue(ex instanceof IllegalArgumentException);
        } finally {
            s.unsubscribe();
        }

    }

    @Test
    public void isUtilityClass() {
        Asserts.assertIsUtilityClass(ObservableServerSocket.class);
    }

    @Test
    public void isUtilityClassIO() {
        Asserts.assertIsUtilityClass(IO.class);
    }

    @Test
    public void testCloserWhenDoesNotThrow() {
        AtomicBoolean called = new AtomicBoolean();
        Closeable c = new Closeable() {

            @Override
            public void close() throws IOException {
                called.set(true);
            }
        };
        ObservableServerSocket.closer().call(c);
        assertTrue(called.get());
    }

    @Test
    public void testCloserWhenThrows() {
        IOException ex = new IOException();
        Closeable c = new Closeable() {

            @Override
            public void close() throws IOException {
                throw ex;
            }
        };
        try {
            ObservableServerSocket.closer().call(c);
            Assert.fail();
        } catch (RuntimeException e) {
            assertTrue(ex == e.getCause());
        }
    }

    @Test
    public void testAsynchronousDeliveryWithDefaultAsynchronousChannelGroup()
            throws InterruptedException {
        testAsync(() -> null);
    }

    @Test
    public void testAsynchronousDeliveryWithCustomAsynchronousChannelGroup()
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        testAsync(Checked.f0(() -> AsynchronousChannelGroup.withThreadPool(executor)));
    }

    private void testAsync(Func0<AsynchronousChannelGroup> group) throws InterruptedException {
        Scheduler scheduler = Schedulers.from(Executors.newFixedThreadPool(50));
        AtomicBoolean errored = new AtomicBoolean(false);
        for (int k = 0; k < 1; k++) {
            TestSubscriber<String> ts = TestSubscriber.create();
            try {
                int bufferSize = 4;
                IO.serverSocket(PORT, 10, TimeUnit.SECONDS, bufferSize, BackpressureMode.BUFFER,
                        group) //
                        .flatMap(g -> g //
                                .compose(Bytes.collect()) //
                                .onErrorResumeNext(Observable.empty()), 1) //
                        .map(bytes -> new String(bytes, StandardCharsets.UTF_8)) //
                        .doOnError(e -> e.printStackTrace()) //
                        .doOnError(Actions.setToTrue1(errored)) //
                        .subscribe(ts);
                TestSubscriber<Object> ts2 = TestSubscriber.create();
                Set<String> messages = new ConcurrentSkipListSet<>();

                int messageBlocks = 10;
                int numMessages = 1000;

                // sender
                Observable.range(1, numMessages).flatMap(n -> {
                    return Observable.defer(() -> {
                        // System.out.println(Thread.currentThread().getName() +
                        // " - writing message");
                        String id = UUID.randomUUID().toString();
                        StringBuilder s = new StringBuilder();
                        for (int i = 0; i < messageBlocks; i++) {
                            s.append(id);
                        }
                        messages.add(s.toString());
                        try {
                            Socket socket = new Socket("localhost", PORT);
                            OutputStream out = socket.getOutputStream();
                            for (int i = 0; i < messageBlocks; i++) {
                                out.write(id.getBytes(StandardCharsets.UTF_8));
                            }
                            out.close();
                            socket.close();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        return Observable.just(1);
                    }).subscribeOn(scheduler);
                } , 1).subscribe(ts2);
                ts2.awaitTerminalEvent();
                ts2.assertCompleted();
                // allow server to complete processing
                Thread.sleep(1000);
                assertEquals(messages, new HashSet<String>(ts.getOnNextEvents()));
                assertFalse(errored.get());
            } finally {
                ts.unsubscribe();
            }
        }
    }

    private void checkServerSocketReadsTcpPushWhenBufferSizeIs(String text, int bufferSize)
            throws UnknownHostException, IOException, InterruptedException {
        TestSubscriber<Object> ts = TestSubscriber.create();
        AtomicReference<byte[]> result = new AtomicReference<byte[]>();
        try {
            IO.serverSocket(PORT, 10, TimeUnit.SECONDS, bufferSize, BackpressureMode.BUFFER) //
                    .flatMap(g -> g //
                            .compose(Bytes.collect()) //
                            .doOnNext(Actions.setAtomic(result)) //
                            .doOnNext(bytes -> System.out.println(
                                    Thread.currentThread().getName() + ": " + new String(bytes))) //
                            .onErrorResumeNext(Observable.empty()))
                    .subscribe(ts);
            Socket socket = new Socket("localhost", PORT);
            OutputStream out = socket.getOutputStream();
            out.write(text.getBytes());
            out.close();
            socket.close();
            Thread.sleep(1000);
            assertEquals(text, new String(result.get(), StandardCharsets.UTF_8));
        } finally {
            // will close server socket
            ts.unsubscribe();
        }
    }

}
