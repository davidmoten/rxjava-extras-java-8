package com.github.davidmoten.rx;

import java.util.Arrays;

import rx.Notification;

public final class ConnectionNotification {

    private final String id;
    private final Notification<byte[]> notification;

    public ConnectionNotification(String id, Notification<byte[]> notification) {
        this.id = id;
        this.notification = notification;
    }

    public String id() {
        return id;
    }

    public Notification<byte[]> notification() {
        return notification;
    }

    @Override
    public String toString() {
        String n;
        if (notification.hasValue()) {
            n = Arrays.toString(notification.getValue());
        } else {
            n = notification.toString();
        }
        return "ConnectionNotification [id=" + id + ", notification=" + n + "]";
    }

}