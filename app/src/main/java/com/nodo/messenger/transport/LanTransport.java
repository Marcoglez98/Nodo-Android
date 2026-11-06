package com.nodo.messenger.transport;

import com.nodo.messenger.LocalNode;

/** Adapter exposing the current, tested LocalNode TCP/mDNS transport. */
public final class LanTransport implements NearbyTransport {
    private final LocalNode node;
    public LanTransport(LocalNode node){this.node=node;}
    @Override public Mode mode(){return Mode.WIFI_LAN;}
    @Override public boolean available(){return node!=null;}
    @Override public boolean isConnected(String peerId){return node!=null&&node.isConnected(peerId);}
    @Override public String details(){return "Wi-Fi local / punto de acceso, sin requerir salida a internet";}
}
