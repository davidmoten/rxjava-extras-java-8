# rxjava-extras-java-8

* `Obs2.serverSocket` - Uses NIO to listen on a server socket and emit the byte array chunks in an Observable stream (identified by a unique connection id)

##Obs2.serverSocket

Usage:

The example below emits the byte arrays delivered by TCP push to a server socket localhost:12345 to the console. If a connection drops out then the bytes received are not emitted.

```java
Action2<ByteArrayOutputStream, byte[]> collector = (bos, bytes) -> {
        try {
            bos.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    };

Obs2.serverSocket(12345, 10, TimeUnit.SECONDS)
    .groupBy(cn -> cn.id())
    .flatMap(g -> 
        g.map(cn -> cn.notification())
         .<byte[]> dematerialize()
         .collect(() -> new ByteArrayOutputStream(), collector)
         .map(bos -> bos.toByteArray())
         .doOnNext(bytes -> System.out.println(new String(bytes)))
         .onErrorResumeNext(Observable.empty()))
    .subscribe(subscriber);

// a call to unsubscribe will cancel
// the stream and close the server socket
subscriber.unsubscribe();
```
             .
                       .
