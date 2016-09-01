# rxjava-extras-java-8

<a href="https://travis-ci.org/davidmoten/rxjava-extras-java-8"><img src="https://travis-ci.org/davidmoten/rxjava-extras-java-8.svg"/></a><br/>
[![codecov](https://codecov.io/gh/davidmoten/rxjava-extras-java-8/branch/master/graph/badge.svg)](https://codecov.io/gh/davidmoten/rxjava-extras-java-8)<br/>
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.davidmoten/rxjava-extras-java-8/badge.svg?style=flat)](https://maven-badges.herokuapp.com/maven-central/com.github.davidmoten/rxjava-extras-java-8)


* `IO.serverSocket` - Uses NIO to listen on a server socket and emit the byte array chunks in an Observable stream (identified by a unique connection id)

##IO.serverSocket

###Usage

The example below emits the bytes delivered by each TCP connection to the server socket `localhost:12345` to the console as a string. If a connection drops out then the bytes received are not emitted.

```java
//accumulates bytes into a single byte array 
Action2<ByteArrayOutputStream, byte[]> collector = (bos, bytes) -> {
        try {
            bos.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    };
int PORT = 12345;
long timeoutSeconds = 10;
// will use a buffer of 8192 bytes by default
IO.serverSocket(port, timeoutSeconds, TimeUnit.SECONDS)
    // group by connection id
    .groupBy(cn -> cn.id())
    // handle each connection as a separate stream
    .flatMap(g -> 
        // g is the stream of notifications for one connection 
        // note that g.getKey() will return the connection id
        g.map(cn -> cn.notification())
         // turn into a stream of byte[]
         .<byte[]> dematerialize()
         // accumulate the byte[] into one byte[]
         .collect(() -> new ByteArrayOutputStream(), collector)
         // return the accumulated byte[]
         .map(bos -> bos.toByteArray())
         // if any error occurred with the stream then emit nothing
         // and complete this connections stream (which will release its
         // entry from the internal map maintained by groupBy). 
         // We don't emit an error because it would should down all 
         // other connections as well. 
         .onErrorResumeNext(Observable.empty())
         // print the byte array message for one connection
         // to the console as a string
         .doOnNext(bytes -> System.out.println(new String(bytes))))
    .subscribe(subscriber);

// a call to unsubscribe will cancel
// the stream and close the server socket
subscriber.unsubscribe();
```
             .
                       .
