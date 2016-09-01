package com.github.davidmoten.rx.internal.operators;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.github.davidmoten.rx.Obs2;

import rx.Observable;

public final class SourceServerSocketTest {

    @Test
    public void serverSocketReadsTcpPush()
            throws UnknownHostException, IOException, InterruptedException {
        Obs2.serverSocket(12345, 10, TimeUnit.SECONDS) //
                .groupBy(cn -> cn.id()) //
                .flatMap(g -> g.map(cn -> cn.notification()).<byte[]> dematerialize()
                        .doOnNext(bytes -> System.out.println(new String(bytes))) //
                        .onErrorResumeNext(Observable.empty()))
                .subscribe();
        Socket socket = new Socket("localhost", 12345);
        OutputStream out = socket.getOutputStream();
        out.write("hello there".getBytes());
        out.close();
        socket.close();
        Thread.sleep(10000);
    }

}
