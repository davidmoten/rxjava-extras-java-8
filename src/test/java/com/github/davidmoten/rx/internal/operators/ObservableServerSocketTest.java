package com.github.davidmoten.rx.internal.operators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

import com.github.davidmoten.junit.Asserts;
import com.github.davidmoten.rx.Actions;
import com.github.davidmoten.rx.IO;

import rx.Observable;
import rx.functions.Action2;
import rx.observers.TestSubscriber;

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
            IO.serverSocket(PORT, 10, TimeUnit.SECONDS, 5).subscribe(ts);
            ts.assertNoValues();
            ts.assertNotCompleted();
            ts.assertTerminalEvent();
            assertTrue(ts.getOnErrorEvents().get(0).getCause() instanceof BindException);
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

    private void checkServerSocketReadsTcpPushWhenBufferSizeIs(String text, int bufferSize)
            throws UnknownHostException, IOException, InterruptedException {
        TestSubscriber<Object> ts = TestSubscriber.create();
        AtomicReference<byte[]> result = new AtomicReference<byte[]>();
        try {
            IO.serverSocket(PORT, 10, TimeUnit.SECONDS, bufferSize) //
                    .groupBy(cn -> cn.id()) //
                    .flatMap(g -> g //
                            .doOnNext(cn -> System.out.println("cn=" + cn)) //
                            .map(cn -> cn.notification()) //
                            .<byte[]> dematerialize() //
                            .collect(() -> new ByteArrayOutputStream(), COLLECTOR) //
                            .map(bos -> bos.toByteArray()) //
                            .doOnNext(Actions.setAtomic(result)) //
                            .doOnNext(bytes -> System.out.println(
                                    Thread.currentThread().getName() + ": " + new String(bytes))) //
                            .onErrorResumeNext(Observable.empty()))
                    .subscribe(ts);
            Socket socket = new Socket("localhost", 12345);
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

    private static final Action2<ByteArrayOutputStream, byte[]> COLLECTOR = (bos, bytes) -> {
        try {
            bos.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    };

}
