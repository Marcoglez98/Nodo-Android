package com.nodo.messenger.transport;

/** Placeholder with honest capability reporting until the real transport is added. */
public final class PlannedTransport implements NearbyTransport {
    private final Mode mode;
    public PlannedTransport(Mode mode){this.mode=mode;}
    @Override public Mode mode(){return mode;}
    @Override public boolean available(){return false;}
    @Override public boolean isConnected(String peerId){return false;}
    @Override public String details(){
        switch(mode){
            case WIFI_DIRECT:return "Wi-Fi Direct requiere negociación P2P y permisos de Android";
            case BLUETOOTH:return "Bluetooth requiere descubrimiento y transporte seguro";
            case MOBILE_DATA:return "Datos móviles requieren señalización y relay seguro";
            default:return "No disponible";
        }
    }
}
