package dev.localspoof.locationprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import java.util.Locale;

public final class ProbeService extends Service {
    private static final String[] NAMES = {"GPS", "Network", "Google Fused"};
    private static final Object LOCK = new Object();
    private static final Location[] latest = new Location[3];
    private static final long[] callbacks = new long[3];
    private static final String[] status = {"Not observing", "Not observing", "Not observing"};
    private static final String[] baseline = {"Not read", "Not read", "Not read"};
    private static boolean observing;
    private static long generation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private FusedLocationProviderClient fused;
    private final LocationListener[] listeners = new LocationListener[2];
    private LocationCallback fusedCallback;
    private long owner;
    private boolean running;
    private long startedAt;

    static void clear() {
        synchronized (LOCK) {
            generation++;
            observing = false;
            for (int i = 0; i < 3; i++) { latest[i] = null; callbacks[i] = 0; status[i] = "Not observing"; baseline[i] = "Not read"; }
        }
    }
    static void startFailed() { synchronized (LOCK) { status[0] = "Start failed: check permissions and open observer again"; } }
    private static long age(Location point) {
        return Math.max(0, (SystemClock.elapsedRealtimeNanos() - point.getElapsedRealtimeNanos()) / 1_000_000L);
    }
    @SuppressWarnings("deprecation")
    private static boolean mock(Location point) { return android.os.Build.VERSION.SDK_INT >= 31 ? point.isMock() : point.isFromMockProvider(); }
    private static String describe(Location point) {
        if (point == null) return "No reading";
        return String.format(Locale.ROOT, "age %d ms · mock %s · speed %s", age(point), mock(point) ? "yes" : "no", point.hasSpeed() ? String.format(Locale.ROOT, "%.2f mph", point.getSpeed() * 2.2369362921) : "unknown");
    }
    private void sample(int source, Location point, boolean cached) {
        synchronized (LOCK) {
            if (!running || !observing || owner != generation || point == null) return;
            if (cached) { baseline[source] = describe(point); return; }
            callbacks[source]++;
            if (latest[source] != null && point.getElapsedRealtimeNanos() <= latest[source].getElapsedRealtimeNanos()) return;
            latest[source] = new Location(point);
            status[source] = "Receiving callbacks";
        }
    }
    private void failure(int source, String message) {
        synchronized (LOCK) { if (running && owner == generation && observing) status[source] = message; }
    }
    static String summary(Context context) {
        synchronized (LOCK) {
            boolean airplane = Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
            StringBuilder text = new StringBuilder(observing ? "Observing" : "Stopped / readings cleared");
            text.append("\nAirplane mode: ").append(airplane ? "ON" : "OFF");
            for (int i = 0; i < 3; i++) {
                text.append("\n\n").append(NAMES[i]).append("\n").append(status[i]);
                text.append("\nCallback count: ").append(callbacks[i]);
                text.append("\nLatest callback: ").append(describe(latest[i]));
                text.append("\nCached at start (not a fresh callback): ").append(baseline[i]);
                if (i > 0) {
                    Location gps = latest[0], other = latest[i];
                    boolean paired = gps != null && other != null && age(gps) <= 3000 && age(other) <= 3000
                        && Math.abs(gps.getElapsedRealtimeNanos() - other.getElapsedRealtimeNanos()) <= 1_000_000_000L;
                    text.append("\nDifference from GPS: ").append(paired ? String.format(Locale.ROOT, "%.1f m", gps.distanceTo(other)) : "not paired / stale");
                }
            }
            return text.toString();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        clear();
        synchronized (LOCK) { observing = true; owner = generation; }
        running = true;
        startedAt = SystemClock.elapsedRealtime();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("observe", "Location observation", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ProbeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProbeService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "observe").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location observer running").setContentText("Observing only; no location changes or history")
            .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop and clear", stop).build()).build();
        try { startForeground(201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); }
        catch (RuntimeException failure) { stopSelf(); startFailed(); return; }
        manager = getSystemService(LocationManager.class);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            failure(0, "Precise location permission required");
            stopSelf();
            return;
        }
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        for (int i = 0; i < 2; i++) {
            final int source = i;
            listeners[i] = new LocationListener() {
                @Override public void onLocationChanged(Location point) { sample(source, point, false); }
                @Override public void onProviderEnabled(String name) { failure(source, "Provider enabled; awaiting callback"); }
                @Override public void onProviderDisabled(String name) { failure(source, "Provider disabled"); }
                @Override public void onStatusChanged(String name, int status, Bundle extras) { }
            };
            try {
                sample(i, manager.getLastKnownLocation(providers[i]), true);
                manager.requestLocationUpdates(providers[i], 200, 0, listeners[i], Looper.getMainLooper());
                failure(i, "Registered; awaiting fresh callback");
            } catch (RuntimeException failure) { failure(i, "Registration unavailable; check permission/provider"); }
        }
        fused = LocationServices.getFusedLocationProviderClient(this);
        fusedCallback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult result) { sample(2, result.getLastLocation(), false); }
        };
        try {
            fused.getLastLocation().addOnSuccessListener(point -> sample(2, point, true))
                .addOnFailureListener(failure -> failure(2, "Cached read unavailable"));
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 200)
                .setMinUpdateIntervalMillis(200).setMaxUpdateDelayMillis(0).setWaitForAccurateLocation(false).build();
            fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                .addOnSuccessListener(unused -> {
                    if (!running) { fused.removeLocationUpdates(fusedCallback); return; }
                    failure(2, "Registered; awaiting fresh callback");
                })
                .addOnFailureListener(failure -> failure(2, "Registration unavailable; check Google services/permission"));
        } catch (RuntimeException failure) { failure(2, "Google location unavailable"); }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (SystemClock.elapsedRealtime() - startedAt >= 30 * 60 * 1000L) stopSelf();
                else main.postDelayed(this, 1000);
            }
        }, 1000);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (manager != null) for (LocationListener listener : listeners) if (listener != null) manager.removeUpdates(listener);
        if (fused != null && fusedCallback != null) fused.removeLocationUpdates(fusedCallback);
        synchronized (LOCK) { if (owner == generation) clear(); }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
