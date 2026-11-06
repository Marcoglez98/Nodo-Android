package com.nodo.messenger.transport;

/** Network capabilities are explicit: unsupported transports must never appear connected. */
public interface NearbyTransport {
    enum Mode { WIFI_LAN, WIFI_DIRECT, BLUETOOTH, MOBILE_DATA }
    Mode mode();
    boolean available();
    boolean isConnected(String peerId);
    String details();
}
