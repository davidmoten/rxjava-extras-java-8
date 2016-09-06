# rxjava-extras-java-8

<a href="https://travis-ci.org/davidmoten/rxjava-extras-java-8"><img src="https://travis-ci.org/davidmoten/rxjava-extras-java-8.svg"/></a><br/>
[![codecov](https://codecov.io/gh/davidmoten/rxjava-extras-java-8/branch/master/graph/badge.svg)](https://codecov.io/gh/davidmoten/rxjava-extras-java-8)<br/>
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.davidmoten/rxjava-extras-java-8/badge.svg?style=flat)](https://maven-badges.herokuapp.com/maven-central/com.github.davidmoten/rxjava-extras-java-8)

Status: *pre-alpha* (not ready yet!)

* `IO.serverSocket` - Uses NIO to listen on a server socket and Observables of byte array chunks for each connection

##IO.serverSocket
This method was created to support a somewhat bizarre legacy message delivery system. As the server doesn't communicate a response to the client at end of stream message delivery is not guaranteed.

###Usage

The example below emits the bytes delivered by each TCP connection to the server socket `localhost:12345` to the console as a string. If a connection drops out then the bytes received are not emitted.

```java
int port = 12345;
long timeoutSeconds = 10;
// will use a buffer of 8192 bytes by default
IO.serverSocket(port, timeoutSeconds, TimeUnit.SECONDS)
    // handle each connection as a separate stream
    .flatMap(g -> 
        // g is the stream of bytes for one connection 
         // accumulate the byte[] into one byte[]
         .transform(Bytes.collect()) 
         // if any error occurs then emit nothing for this connection
         // and complete. We don't emit an error because it would 
         // shut down all other connections as well. 
         .onErrorResumeNext(Observable.empty())
         // print the byte array message for one connection
         // to the console as a string
         .doOnNext(bytes -> System.out.println(new String(bytes))))
    .subscribe(subscriber);

// a call to unsubscribe will cancel any 
// blocking reads (by closing the socket)
// and close the server socket
subscriber.unsubscribe();
```
             .
                       .
