package dev.localspoof.locationprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Optional development observer; no mock submission, history, files or account access. */
public final class ProbeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView readings;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            readings.setText(ProbeService.summary(ProbeActivity.this));
            main.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView explanation = new TextView(this);
        explanation.setText("Location delivery observer\n\nObserves GPS, network and Google Fused Location. It never changes location or airplane mode. No coordinates, files or history are displayed or saved. Stop clears all readings. Observations expire after 30 minutes.\n\nStart only while the phone is unlocked. Leave this running while checking LocalSpoof and your chosen flight journey.");
        explanation.setTextSize(18);
        content.addView(explanation);
        Button start = new Button(this);
        start.setText("Start observation");
        start.setOnClickListener(view -> {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}, 1);
                return;
            }
            try { startForegroundService(new Intent(this, ProbeService.class)); }
            catch (RuntimeException failure) { ProbeService.startFailed(); }
        });
        content.addView(start);
        Button stop = new Button(this);
        stop.setText("Stop and clear readings");
        stop.setOnClickListener(view -> {
            stopService(new Intent(this, ProbeService.class));
            ProbeService.clear();
        });
        content.addView(stop);
        readings = new TextView(this);
        readings.setTextSize(18);
        readings.setTextIsSelectable(false);
        content.addView(readings);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override public void onResume() { super.onResume(); main.post(refresh); }
    @Override public void onPause() { main.removeCallbacks(refresh); super.onPause(); }
}
