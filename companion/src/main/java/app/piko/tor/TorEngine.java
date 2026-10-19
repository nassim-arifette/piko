package app.piko.tor;

import android.os.ParcelFileDescriptor;
import org.torproject.onionmasq.OnionMasqJni;

import java.io.IOException;

final class TorEngine {
    private static Thread worker;
    private static Object owner;
    private static boolean initialized;
    private static boolean stopping;

    private TorEngine() {}

    static synchronized boolean start(Object client, ParcelFileDescriptor tun, String cache,
            String data, OnionMasqJni.Listener listener, Runnable exited) throws IOException {
        if (worker != null) return false;
        if (!initialized) {
            OnionMasqJni.init();
            initialized = true;
        }
        ParcelFileDescriptor engineFd = ParcelFileDescriptor.dup(tun.getFileDescriptor());
        owner = client;
        stopping = false;
        OnionMasqJni.listener = listener;
        worker = new Thread(() -> {
            int fd = -1;
            try {
                // Native runProxy owns this duplicate; the service retains the original TUN FD.
                fd = engineFd.detachFd();
                OnionMasqJni.runProxy(fd, cache, data, null, 0, null);
            } catch (UnsatisfiedLinkError ignored) {
                // Symbol lookup failed before native could take ownership.
                if (fd >= 0) {
                    try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (IOException ignoredClose) { }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Native exception text may contain destinations. Only report a generic state.
            } finally {
                try { engineFd.close(); } catch (IOException ignored) { }
                synchronized (TorEngine.class) {
                    OnionMasqJni.listener = null;
                    worker = null;
                    owner = null;
                    stopping = false;
                }
                exited.run();
            }
        }, "piko-tor-engine");
        worker.start();
        return true;
    }

    static synchronized void stop(Object client) {
        if (owner != client || worker == null || stopping) return;
        stopping = true;
        OnionMasqJni.listener = null;
        Thread running = worker;
        // A stop before runProxy creates its control channel must not be lost.
        Thread closer = new Thread(() -> {
            boolean sent = false;
            while (running.isAlive()) {
                synchronized (TorEngine.class) {
                    if (worker != running) return;
                    try {
                        if (!sent && OnionMasqJni.isRunning()) {
                            OnionMasqJni.closeProxy();
                            sent = true;
                        }
                    } catch (RuntimeException | LinkageError ignored) {
                        // Retry until the in-flight native invocation exits.
                    }
                }
                try {
                    running.join(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "piko-tor-stop");
        closer.setDaemon(true);
        closer.start();
    }

    static synchronized void refresh(Object client) {
        if (owner == client && !stopping && worker != null && OnionMasqJni.isRunning()) {
            OnionMasqJni.refreshCircuits();
        }
    }
}
