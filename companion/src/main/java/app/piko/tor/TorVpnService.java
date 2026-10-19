package app.piko.tor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import org.json.JSONObject;
import org.torproject.onionmasq.OnionMasqJni;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class TorVpnService extends VpnService {
    static final String START = "app.piko.tor.START";
    static final String REFRESH = "app.piko.tor.REFRESH";
    static final String STOP = "app.piko.tor.STOP";
    private static volatile ProtectionState currentState = ProtectionState.OFF;
    private static volatile boolean serviceActive;
    private ProtectionState notifiedState;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ParcelFileDescriptor tun;
    private TargetConfig target;
    private boolean requested;
    private boolean started;
    private boolean failed;
    private volatile boolean ready;
    private volatile boolean destroyed;
    private volatile boolean revoked;
    private volatile boolean permitSockets;
    private volatile int engineGeneration;

    static ProtectionState state() { return currentState; }
    static boolean active() { return serviceActive || TorEngine.hasWorker(); }

    @Override public void onCreate() {
        super.onCreate();
        serviceActive = true;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("tor", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification());
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) {
            requested = false;
            TargetConfig.preferences(this).edit().putBoolean("requested", false).apply();
            shutdown(ProtectionState.OFF);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (START.equals(action)) {
            TargetConfig.preferences(this).edit().putBoolean("requested", true).apply();
        }
        requested = TargetConfig.preferences(this).getBoolean("requested", false);
        if (!requested) {
            shutdown(ProtectionState.OFF);
            stopSelf();
            return START_NOT_STICKY;
        }
        serviceActive = true;
        revoked = prepare(this) != null;
        if (target == null) target = TargetConfig.load(this);
        if (REFRESH.equals(action)) {
            failed = false;
            try { TorEngine.refresh(this); } catch (RuntimeException ignored) { ready = false; }
        }
        ensureTunnel();
        handler.removeCallbacks(tick);
        tick.run();
        return START_STICKY;
    }

    private void ensureTunnel() {
        if (tun != null || !target.valid(this) || prepare(this) != null) return;
        try {
            tun = new Builder().setSession(getString(R.string.app_name))
                    .setConfigureIntent(openApp()).setMtu(1500)
                    .addAllowedApplication(target.packageName)
                    .addAddress("169.254.42.1", 16).addAddress("fc00::", 7)
                    .addRoute("0.0.0.0", 0).addRoute("::", 0)
                    .addDnsServer("169.254.42.53").addDnsServer("fe80::53")
                    .setMetered(false).establish();
        } catch (Exception ignored) {
            failed = true;
        }
    }

    private boolean networkAvailable;

    private boolean strictPrivateDns() {
        networkAvailable = false;
        boolean strictDns = false;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            LinkProperties properties = manager.getLinkProperties(network);
            if (capabilities != null && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                networkAvailable = true;
                strictDns |= properties != null && properties.getPrivateDnsServerName() != null;
            }
        }
        return strictDns;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed || !requested) return;
            currentState = ProtectionState.evaluate(requested, target.valid(TorVpnService.this),
                    tun != null && !revoked && prepare(TorVpnService.this) == null,
                    isAlwaysOn(), isLockdownEnabled(), strictPrivateDns(), failed, ready, networkAvailable);
            permitSockets = currentState.permitsEngine();
            if (!permitSockets && started) {
                ready = false;
                started = false;
                engineGeneration++;
                TorEngine.stop(TorVpnService.this);
            } else if (permitSockets && !started) {
                try {
                    ready = false;
                    int generation = ++engineGeneration;
                    started = TorEngine.start(TorVpnService.this, tun,
                            getCacheDir().getAbsolutePath(), getFilesDir().getAbsolutePath(),
                            listener(generation), () -> handler.post(() -> {
                                if (!destroyed && generation == engineGeneration) {
                                    started = false;
                                    ready = false;
                                    failed = true;
                                }
                            }));
                } catch (IOException | RuntimeException | LinkageError ignored) {
                    failed = true;
                    ready = false;
                }
            }
            if (currentState != notifiedState) {
                getSystemService(NotificationManager.class).notify(1, notification());
                notifiedState = currentState;
            }
            handler.postDelayed(this, 1000);
        }
    };

    private OnionMasqJni.Listener listener(int generation) {
        return new OnionMasqJni.Listener() {
            @Override public boolean protect(int socket) {
                return generation == engineGeneration && !destroyed && !revoked && permitSockets
                        && isAlwaysOn() && isLockdownEnabled() && TorVpnService.this.protect(socket);
            }

            @Override public int owner(int protocol, byte[] source, int sourcePort, byte[] destination, int destinationPort) {
                return generation == engineGeneration
                        ? connectionOwner(protocol, source, sourcePort, destination, destinationPort) : -1;
            }

            @Override public void event(String json) {
                boolean bootstrapReady;
                try {
                    JSONObject event = new JSONObject(json);
                    if (!"Bootstrap".equals(event.optString("type"))) return;
                    bootstrapReady = event.optBoolean("is_ready_for_traffic", false);
                } catch (Exception ignored) {
                    bootstrapReady = false;
                }
                boolean value = bootstrapReady;
                handler.post(() -> {
                    if (!destroyed && permitSockets && generation == engineGeneration) ready = value;
                });
            }
        };
    }

    private int connectionOwner(int protocol, byte[] source, int sourcePort,
            byte[] destination, int destinationPort) {
        if (destroyed || (protocol != OsConstants.IPPROTO_TCP && protocol != OsConstants.IPPROTO_UDP)) return -1;
        try {
            int uid = getSystemService(ConnectivityManager.class).getConnectionOwnerUid(protocol,
                    new InetSocketAddress(InetAddress.getByAddress(source), sourcePort),
                    new InetSocketAddress(InetAddress.getByAddress(destination), destinationPort));
            return uid == target.uid ? uid : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        return new Notification.Builder(this, "tor").setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(getString(R.string.app_name)).setContentText(MainActivity.describe(this, currentState))
                .setContentIntent(openApp()).setOngoing(true).build();
    }

    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }

    @Override public void onRevoke() {
        revoked = true;
        shutdown(requested ? ProtectionState.VPN_MISSING : ProtectionState.OFF);
        stopSelf();
    }

    private void shutdown(ProtectionState state) {
        permitSockets = false;
        ready = false;
        started = false;
        failed = false;
        engineGeneration++;
        handler.removeCallbacks(tick);
        TorEngine.stop(this);
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) { }
            tun = null;
        }
        target = null;
        serviceActive = false;
        currentState = state;
        notifiedState = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override public void onDestroy() {
        destroyed = true;
        shutdown(requested ? ProtectionState.VPN_MISSING : ProtectionState.OFF);
        super.onDestroy();
    }
}
