package com.stevi2k.atvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps the phone control page running while other apps are on screen. A foreground service, so
 * Android doesn't kill the launcher process to free memory while you watch something.
 */
public class PhoneService extends Service {

    private PhoneServer server;

    // Performance overlay: a small, non-touchable HUD in the top-right corner,
    // shown over Home and over other apps while the toggle is on. It lives in
    // this already-foreground service so no second service or notification is
    // needed, and it restarts with Home after a reboot or an update.
    private android.view.WindowManager wm;
    private android.widget.TextView overlayView;
    private final android.os.Handler overlayHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long prevCpuTotal, prevCpuIdle;
    private boolean prevCpuSet;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("phone", "Phone control", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "phone")
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("Phone control is on")
                .build();
        startForeground(1, n);
        server = new PhoneServer(this);
        server.start();
        if (getSharedPreferences("home", MODE_PRIVATE).getBoolean("performance_overlay", false)) showOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "overlay_on".equals(intent.getAction())) showOverlay();
        else if (intent != null && "overlay_off".equals(intent.getAction())) hideOverlay();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        if (server != null) server.stop();
        super.onDestroy();
    }

    private void showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        if (overlayView != null) return;
        wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = Ui.text(this, 500, 14, 0xFFFFFFFF);
        overlayView.setBackground(Ui.rounded(0xCC000000, Ui.dp(this, 12), Ui.dp(this, 1), 0x66FFFFFF));
        overlayView.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        overlayView.setText("CPU --%  RAM --");
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.os.Build.VERSION.SDK_INT >= 26
                        ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        lp.x = Ui.dp(this, 16);
        lp.y = Ui.dp(this, 16);
        try { wm.addView(overlayView, lp); } catch (Exception e) { overlayView = null; return; }
        overlayHandler.post(overlayTick);
    }

    private void hideOverlay() {
        overlayHandler.removeCallbacks(overlayTick);
        if (overlayView != null && wm != null) {
            try { wm.removeView(overlayView); } catch (Exception ignored) {}
        }
        overlayView = null;
    }

    private final Runnable overlayTick = new Runnable() {
        @Override
        public void run() {
            if (overlayView != null) overlayView.setText(overlayText());
            overlayHandler.postDelayed(this, 1000);
        }
    };

    /** "CPU 23%  RAM 1.2/2.0 GB": CPU from /proc/stat deltas, RAM from ActivityManager. */
    private String overlayText() {
        int cpu = cpuPercent();
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double totalGb = mi.totalMem / 1073741824.0;
        double usedGb = (mi.totalMem - mi.availMem) / 1073741824.0;
        return "CPU " + cpu + "%  RAM " + String.format(java.util.Locale.US, "%.1f/%.1f GB", usedGb, totalGb);
    }

    private int cpuPercent() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"));
            String line = r.readLine();
            r.close();
            if (line == null || !line.startsWith("cpu ")) return 0;
            String[] p = line.trim().split("\\s+");
            long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
            long total = 0;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            if (!prevCpuSet) { prevCpuTotal = total; prevCpuIdle = idle; prevCpuSet = true; return 0; }
            long dTotal = total - prevCpuTotal, dIdle = idle - prevCpuIdle;
            prevCpuTotal = total; prevCpuIdle = idle;
            if (dTotal <= 0) return 0;
            return (int) ((dTotal - dIdle) * 100 / dTotal);
        } catch (Exception e) { return 0; }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
