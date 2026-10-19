package org.torproject.onionmasq;

import android.os.Build;

/** Minimal ABI adapter for the pinned Onionmasq JNI exports; no bypass/PCAP/UDP relay API. */
public final class OnionMasqJni {
    public interface Listener {
        boolean protect(int socket);
        int owner(int protocol, byte[] source, int sourcePort, byte[] destination, int destinationPort);
        void event(String json);
    }

    public static volatile Listener listener;

    public static native void init();
    public static native void runProxy(int fd, String cache, String data, String pt, long port, String bridges);
    public static native void closeProxy();
    public static native boolean isRunning();
    public static native void refreshCircuits();

    public static boolean protect(int socket) {
        Listener current = listener;
        return current != null && current.protect(socket);
    }

    public static int getConnectionOwnerUid(int protocol, byte[] source, int sourcePort,
            byte[] destination, int destinationPort) {
        Listener current = listener;
        return current == null ? -1 : current.owner(protocol, source, sourcePort, destination, destinationPort);
    }

    public static int getAndroidAPI() {
        return Build.VERSION.SDK_INT;
    }

    public static void postEvent(String json) {
        Listener current = listener;
        if (current != null) current.event(json);
    }

    static {
        System.loadLibrary("onionmasq_mobile");
    }
}
