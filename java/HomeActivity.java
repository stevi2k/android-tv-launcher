package com.stevi2k.atvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. All D-pad navigation is handled here (no Android focus search), so
 * movement is deterministic: zones TOP (pills) / DOCK / GRID, Up/Down move exactly one row.
 */
public class HomeActivity extends Activity implements Wallpapers.Listener {

    private enum Zone { TOP, DOCK, GRID }

    private static final int PILLS = 4;
    /** apps per row, dock and grid (config.xml: columns) */
    private int COLS;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Weather and follower counts used to share the app-loading thread, so
    // a slow network call (up to ~32 s with the IP lookup) delayed the
    // home screen itself. They now run on their own thread.
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private long lastWeatherAt;
    private long weatherIpFailedAt;
    private boolean homeBitmapsFreed;
    private boolean receiversOn;
    private SharedPreferences prefs;

    // layout (px)
    private int W, H, side, bandTop, gridTop, rowH, dockCell, dockCardW;

    private FrameLayout root, content;
    private TextView weatherText, clock, date, dockTitle, wifiText;

    // weather (Open-Meteo, no key): replaces the greeting line
    private String weather = "";

    // rearrange: hold OK on an app icon to jiggle, arrows move it, OK opens its menu
    private boolean editMode;
    private ObjectAnimator jiggleAnim;
    private View jiggleCard;
    private long centerDownAt;
    private boolean centerHeld, ignoreCenterUp;

    // Hidden folder: a grid tile, visible to every profile, but its contents are
    // per profile; the user picks what goes in for their own profile only
    private static final String FOLDER_PKG = "__hidden_folder__";
    private static final String SETTINGS_PKG = "__launcher_settings__";
    private AppRepo.App folderApp;
    private AppRepo.App settingsApp;
    private FrameLayout folderOverlay;
    private RecyclerView folderRv;
    private TileAdapter folderAdapter;
    private TextView folderEmpty;
    private int folderIdx;
    private boolean folderOpen;

    // small menu for one icon (Done / Move to Hidden folder, or Done / Unhide in the folder)
    private FrameLayout editMenuOverlay;
    private LinearLayout editMenuList;
    private TextView editMenuTitle;
    private final List<View> editMenuItems = new ArrayList<>();
    private final List<String> editMenuActions = new ArrayList<>();
    private int editMenuIdx;
    private boolean editMenuOpen;
    private AppRepo.App editMenuApp;
    private LinearLayout pillRow;
    // follower counters under the pills: YouTube, Instagram, Facebook
    private static final String[] SOCIAL_KEYS = {"yt", "ig", "fb"};
    private static final String[] SOCIAL_UNITS = {"subscribers", "followers", "followers"};
    private final String[] socialIds = new String[3];
    private final View[] socialPills = new View[3];
    private final TextView[] socialTexts = new TextView[3];
    private boolean hasSocial;
    private static final long SUBS_REFRESH_MS = 30 * 60_000L;
    private final View[] pills = new View[PILLS];
    private TextView profileLabel;

    // profiles: name + dock + kid flag, from config.xml (profiles array)
    private static final class Profile {
        String name;
        final List<String> dock = new ArrayList<>();
        boolean kid;
    }
    private final List<Profile> profiles = new ArrayList<>();
    // Set when Home starts the first-launch wizard, cleared in onPause, so
    // the onResume gate below does not start a second wizard on the very
    // first launch (onCreate already started one).
    private boolean wizardLaunched;
    private int profileIdx;
    private String lastProfileJson = "";
    private static final int[] PROFILE_COLORS =
            {0xFF5B8DEF, 0xFFB678E8, 0xFF58C98A, 0xFFF2A65A, 0xFFE05A7A, 0xFF4FC3C3};
    private FrameLayout pickerOverlay;
    private final List<View> pickerCards = new ArrayList<>();
    private int pickerIdx;
    private boolean pickerOpen;
    // Fingerprint of the last apps load, so onResume can tell if the
    // Wizard (TV or phone) changed docks, hidden lists or grid order
    // while Home was in the background. Without this, Home kept showing
    // the old dock and grid, and hidden apps stayed visible until a
    // restart, which is exactly the hidden-apps bug reported during testing.
    private String lastAppsFingerprint = "";

    private GlassHighlight highlight;
    private View glow;
    private GradientDrawable glowBg;
    private RecyclerView dockRv, gridRv;
    private TileAdapter dockAdapter, gridAdapter;
    private InputsMenu inputs;

    private Zone zone = Zone.DOCK;
    private int topIdx, dockIdx, gridIdx;
    private final RectF hl = new RectF();
    private float hlRadius, hlRadiusNow;
    private boolean hlPlaced;
    private ValueAnimator hlAnim, glowAnim;
    private int glowColor = 0;
    private int lastTitleApp = -1;
    private boolean titleChanging;

    // rotating wallpapers: Files from the photo store (see Wallpapers)
    private final List<Object> wallpapers = new ArrayList<>();
    private final ImageView[] wpViews = new ImageView[2];
    private int wpIdx, wpFront;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        weather = prefs.getString("weather", "");
        parseProfiles();
        profileIdx = Math.max(0, Math.min(prefs.getInt("profile", 0), profiles.size() - 1));
        AppRepo.migrateLegacyHidden(this, profileIdx);
        COLS = Math.max(3, Math.min(6, getResources().getInteger(R.integer.columns)));
        zone = Zone.values()[prefs.getInt("zone", Zone.DOCK.ordinal())];
        topIdx = prefs.getInt("top", 0);
        dockIdx = prefs.getInt("dock", 0);
        gridIdx = prefs.getInt("grid", 0);
        if (zone == Zone.TOP) zone = Zone.DOCK;   // come back to apps, not the buttons

        buildUi();
        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // Receivers are registered in onStart / unregistered in onStop, so
        // they do not fire (and reload everything) while Home is paused. 

        loadApps();
        // fresh start (TV boot, first run): ask who is watching before the home screen
        if (b == null) openPicker();

        Wallpapers.setListener(this);
        startForegroundService(new Intent(this, PhoneService.class));
    }

    private String appsFingerprint() {
        List<String> hidden = new ArrayList<>(AppRepo.hiddenPkgs(this, profileIdx));
        java.util.Collections.sort(hidden);
        return profileIdx + "|" + effectiveDockPkgs() + "|" + hidden + "|" + prefs.getString("grid_order", "");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiversOn) {
            IntentFilter time = new IntentFilter();
            time.addAction(Intent.ACTION_TIME_TICK);
            time.addAction(Intent.ACTION_TIME_CHANGED);
            time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            time.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            time.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            registerReceiver(statusReceiver, time);
            IntentFilter pk = new IntentFilter();
            pk.addAction(Intent.ACTION_PACKAGE_ADDED);
            pk.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pk.addAction(Intent.ACTION_PACKAGE_CHANGED);
            pk.addAction(Intent.ACTION_PACKAGE_REPLACED);
            pk.addDataScheme("package");
            registerReceiver(packageReceiver, pk);
            receiversOn = true;
        }
    }

    @Override
    protected void onStop() {
        // Free the home screen's bitmaps while another app is in front, so
        // the foreground service does not keep ~14 MB of banners plus the
        // wallpaper alive under Netflix. onResume reloads them.
        for (ImageView v : wpViews) if (v != null) v.setImageDrawable(null);
        for (TileAdapter ad : new TileAdapter[]{dockAdapter, gridAdapter}) {
            if (ad != null) for (AppRepo.App a : ad.apps) a.banner = null;
        }
        homeBitmapsFreed = true;
        if (receiversOn) {
            try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
            try { unregisterReceiver(packageReceiver); } catch (Exception ignored) {}
            receiversOn = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        updateWifi();
        if (homeBitmapsFreed) {
            homeBitmapsFreed = false;
            loadApps();
            if (wpViews[0] != null && !wallpapers.isEmpty()) {
                wpViews[0].setImageBitmap(loadWallpaper(wallpapers.get(wpIdx % wallpapers.size())));
            }
        }
        refreshProfilesIfChanged();
        // Setup gate, gated so it never runs after setup is done: only
        // while first launch is incomplete AND no user account exists, force
        // the wizard, no matter how Home was reached (fresh start, HOME key,
        // reopening the app). This closes the HOME bypass where resuming an
        // already-alive Home skipped the wizard entirely. Once setup is
        // done, first_launch_done is true and this is skipped outright, so
        // updates and normal opens are untouched.
        if (!wizardLaunched && !prefs.getBoolean("first_launch_done", false) && profiles.isEmpty()) {
            wizardLaunched = true;
            startActivity(new Intent(this, FirstLaunchActivity.class));
            return;
        }
        // Reload only if something actually changed while Home was away,
        // so returning from Netflix etc. does not flicker or re-render.
        int savedProfile = profiles.isEmpty() ? 0
                : Math.max(0, Math.min(prefs.getInt("profile", profileIdx), profiles.size() - 1));
        if (savedProfile != profileIdx) {
            profileIdx = savedProfile;
            if (profileLabel != null && !profiles.isEmpty()) profileLabel.setText(profiles.get(profileIdx).name);
        }
        if (!appsFingerprint().equals(lastAppsFingerprint)) loadApps();
        if (inputs.isOpen()) inputs.close();
        scheduleMinute();
        scheduleWallpaper();
        fetchWeather.run();
        if (hasSocial && System.currentTimeMillis() - prefs.getLong("counts_at", 0) > SUBS_REFRESH_MS) {
            refreshSubs.run();
        } else if (hasSocial) {
            main.postDelayed(refreshSubs, SUBS_REFRESH_MS);
        }
    }

    @Override
    protected void onPause() {
        wizardLaunched = false;
        // nothing ticks (and no wallpaper is decoded) while an app is in front
        main.removeCallbacks(minuteTick);
        main.removeCallbacks(nextWallpaper);
        main.removeCallbacks(refreshSubs);
        main.removeCallbacks(fetchWeather);
        centerDownAt = 0;
        centerHeld = false;
        super.onPause();
    }

    /** Some TV firmwares don't deliver ACTION_TIME_TICK, so the clock runs on our own timer. */
    private final Runnable minuteTick = () -> {
        updateClock();
        // Phone wizard changes land while Home is already on screen, so
        // check here too, not just onResume, and reload only if changed.
        if (!pickerOpen) {
            refreshProfilesIfChanged();
            if (!appsFingerprint().equals(lastAppsFingerprint)) loadApps();
        }
        scheduleMinute();
    };

    private void scheduleMinute() {
        main.removeCallbacks(minuteTick);
        long now = System.currentTimeMillis();
        main.postDelayed(minuteTick, 60_000 - now % 60_000 + 50);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close menus and return to the dock.
        if (inputs.isOpen()) inputs.close();
        if (zone == Zone.GRID && hasWindowFocus()) {
            zone = Zone.DOCK;
            applyFocus(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (receiversOn) {
            try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
            try { unregisterReceiver(packageReceiver); } catch (Exception ignored) {}
            receiversOn = false;
        }
        Wallpapers.setListener(null);
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(a)
                    || WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a)) updateWifi();
            else updateClock();
        }
    };

    private final Runnable debouncedLoad = () -> loadApps();

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Disabling 20 apps fires ~20 broadcasts: debounce to one reload
            // 500 ms after the last one, instead of 20 full banner renders.
            main.removeCallbacks(debouncedLoad);
            main.postDelayed(debouncedLoad, 500);
        }
    };

    private void loadApps() {
        lastAppsFingerprint = appsFingerprint();
        final List<String> dockPkgs = effectiveDockPkgs();
        final Set<String> hidden = AppRepo.hiddenPkgs(this, profileIdx);
        final List<String> gridOrder = splitList(prefs.getString("grid_order", ""));
        io.execute(() -> {
            AppRepo.Result r = AppRepo.load(getApplicationContext(), dockPkgs, hidden, gridOrder);
            main.post(() -> setApps(r));
        });
    }

    /** The dock for this profile (user's saved order when one exists, else the config dock). */
    private List<String> effectiveDockPkgs() {
        return AppRepo.effectiveDock(this, profileIdx);
    }

    private static List<String> splitList(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private void setApps(AppRepo.Result r) {
        // a package change mid-edit just drops edit mode; the fresh lists win
        if (editMode) {
            editMode = false;
            stopJiggle();
        }
        // the Hidden folder tile always closes the grid, then the Launcher Settings tile
        // Always re-draw these two: onStop frees every banner in the adapters
        // to save RAM, and real apps get re-rendered by loadApps on return,
        // but these two cached tiles never did, so after leaving home once
        // they came back as blank dark cards with no folder or gear icon.
        folderApp = makeFolderApp();
        settingsApp = makeSettingsApp();
        List<AppRepo.App> grid = new ArrayList<>(r.grid);
        grid.add(folderApp);
        grid.add(settingsApp);
        dockAdapter.setApps(r.dock);
        gridAdapter.setApps(grid);
        // a short dock centers itself in the band instead of hugging the left
        int n = dockAdapter.getItemCount();
        FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) dockRv.getLayoutParams();
        if (n > 0 && n < COLS) {
            int rowW = (n - 1) * dockCell + dockCardW;
            dlp.width = dockCell * n;
            dlp.leftMargin = (W - rowW) / 2 - (dockCell - dockCardW) / 2;
        } else {
            dlp.width = dockCell * COLS;
            dlp.leftMargin = side + dp(28) - (dockCell - dockCardW) / 2;
        }
        dockRv.setLayoutParams(dlp);
        int rows = (grid.size() + COLS - 1) / COLS;
        ViewGroup.LayoutParams glp = gridRv.getLayoutParams();
        glp.height = Math.max(rows, 1) * rowH;
        gridRv.setLayoutParams(glp);
        ViewGroup.LayoutParams clp = content.getLayoutParams();
        clp.height = gridTop + Math.max(rows, 1) * rowH + Ui.dp(this, 40);
        content.setLayoutParams(clp);
        lastTitleApp = -1;
        clampIndices();
        // Wait for the RecyclerViews to lay out before placing the highlight.
        content.post(() -> content.post(() -> applyFocus(hlPlaced)));
    }

    /** The Hidden folder tile: folder glyph with the word Hidden on it, so it's obvious unfocused. */
    private AppRepo.App makeFolderApp() {
        Bitmap b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFF1E1E21);
        Drawable d = getDrawable(R.drawable.ic_folder);
        int s = 72;
        d.setBounds((320 - s) / 2, 28, (320 + s) / 2, 28 + s);
        d.draw(cv);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setTextSize(30);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        cv.drawText("Hidden", 160, 150, paint);
        AppRepo.App f = new AppRepo.App();
        f.pkg = FOLDER_PKG;
        f.label = "Hidden";
        f.banner = b;
        f.glow = 0xFFB8C4E0;
        return f;
    }

    /** The Launcher Settings tile: opens WizardActivity (backup, debloat, docks, hidden, home). */
    private AppRepo.App makeSettingsApp() {
        Bitmap b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFF1E1E21);
        Drawable d = getDrawable(R.drawable.ic_settings);
        int s = 72;
        if (d != null) {
            d.setBounds((320 - s) / 2, 28, (320 + s) / 2, 28 + s);
            d.draw(cv);
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setTextSize(30);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        cv.drawText("Settings", 160, 150, paint);
        AppRepo.App f = new AppRepo.App();
        f.pkg = SETTINGS_PKG;
        f.label = "Launcher Settings";
        f.banner = b;
        f.glow = 0xFFB8C4E0;
        return f;
    }

    private void clampIndices() {
        int nd = dockAdapter.getItemCount(), ng = gridAdapter.getItemCount();
        dockIdx = Math.max(0, Math.min(dockIdx, nd - 1));
        gridIdx = Math.max(0, Math.min(gridIdx, ng - 1));
        if (zone == Zone.GRID && ng == 0) zone = Zone.DOCK;
        if (zone == Zone.DOCK && nd == 0) zone = ng > 0 ? Zone.GRID : Zone.TOP;
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void buildUi() {
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;
        side = dp(40);
        // cells shrink to fit COLS per row; cards keep the 16:9 TV banner shape
        int gap = dp(24);
        dockCell = (W - 2 * side - 2 * dp(16)) / COLS;
        dockCardW = dockCell - gap;
        int dockCardH = dockCardW * 9 / 16;
        int gridCell = (W - 2 * side + gap) / COLS, gridCardW = gridCell - gap;
        int gridCardH = gridCardW * 9 / 16;
        int bandH = dockCardH + dp(28), bandBottom = dp(26);
        bandTop = H - bandBottom - bandH;
        rowH = gridCardH + dp(42);
        gridTop = H + dp(56);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);

        boolean useWallpaper = getResources().getBoolean(R.bool.use_wallpaper);
        if (useWallpaper) {
            findWallpapers();
            for (int i = 0; i < 2; i++) {
                wpViews[i] = new ImageView(this);
                wpViews[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
                wpViews[i].setAlpha(i == 0 ? 1f : 0f);
                root.addView(wpViews[i], new FrameLayout.LayoutParams(-1, -1));
            }
            if (!wallpapers.isEmpty()) {
                wpIdx = prefs.getInt("wallpaper", 0) % wallpapers.size();
                wpViews[0].setImageBitmap(loadWallpaper(wallpapers.get(wpIdx)));
            }
            // darken: evenly, and more towards the bottom where the dock and grid sit
            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x73000000, 0x80000000, 0xD9000000}));
            root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        }

        content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(-1, H * 2));

        // ambient glow behind the dock title
        glow = new View(this);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // circle fades out before the view edges; scaleY flattens it into an ellipse
        glowBg.setGradientRadius(dp(290));
        glowBg.setGradientCenter(0.4f, 0.5f);
        glowBg.setColors(new int[]{0, 0});
        glow.setBackground(glowBg);
        glow.setScaleY(0.45f);
        int glowCx = side + dp(110), glowCy = bandTop - dp(44);
        content.addView(glow, at(dp(900), dp(600), glowCx - dp(360), glowCy - dp(300)));

        // dock band
        View band = new View(this);
        band.setBackground(Ui.rounded(useWallpaper ? 0x8C141416 : 0xFF141416, dp(28), dp(1), 0x14FFFFFF));
        content.addView(band, at(W - 2 * side, bandH, side, bandTop));

        // the single glass highlight (below tiles and pills)
        highlight = new GlassHighlight(this);
        highlight.setAlpha(0f);
        content.addView(highlight, new FrameLayout.LayoutParams(-1, -1));

        // weather, clock, date
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        weatherText = Ui.text(this, 400, 18, 0xCCFFFFFF);
        clock = Ui.text(this, 150, 78, 0xFFFFFFFF);
        clock.setLetterSpacing(-0.02f);
        date = Ui.text(this, 300, 19, 0xCCFFFFFF);
        tl.addView(weatherText);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = dp(4);
        clp.leftMargin = -dp(3);
        tl.addView(clock, clp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = dp(6);
        tl.addView(date, dlp);
        content.addView(tl, at(W / 2, -2, side, dp(34)));

        // pills: Inputs, Wi-Fi, Settings, current profile (opens the picker)
        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pills[0] = pill(R.drawable.ic_input, "Inputs");
        pills[1] = pill(R.drawable.ic_wifi, "Wi-Fi");
        wifiText = (TextView) ((ViewGroup) pills[1]).getChildAt(1);
        pills[2] = pill(R.drawable.ic_settings, null);
        pills[3] = pill(R.drawable.ic_profile, profiles.isEmpty() ? "Add a user" : profiles.get(profileIdx).name);
        profileLabel = (TextView) ((ViewGroup) pills[3]).getChildAt(1);
        for (int i = 0; i < PILLS; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            if (i > 0) lp.leftMargin = dp(10);
            pillRow.addView(pills[i], lp);
        }
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        plp.topMargin = dp(36);
        plp.rightMargin = side;
        content.addView(pillRow, plp);

        // follower counters, right-aligned under the pills (display only, never focused)
        socialIds[0] = getString(R.string.youtube_channel).trim();
        socialIds[1] = getString(R.string.instagram_user).trim();
        socialIds[2] = getString(R.string.facebook_page).trim();
        int[] logos = {R.drawable.ic_youtube, R.drawable.ic_instagram, R.drawable.ic_facebook};
        LinearLayout counters = new LinearLayout(this);
        counters.setOrientation(LinearLayout.VERTICAL);
        counters.setGravity(Gravity.END);
        for (int i = 0; i < 3; i++) {
            if (socialIds[i].isEmpty()) continue;
            hasSocial = true;
            LinearLayout sp = new LinearLayout(this);
            sp.setOrientation(LinearLayout.HORIZONTAL);
            sp.setGravity(Gravity.CENTER_VERTICAL);
            sp.setBackground(Ui.rounded(0x33000000, dp(16), dp(1), 0x1FFFFFFF));
            sp.setPadding(dp(12), 0, dp(14), 0);
            ImageView logo = new ImageView(this);
            logo.setImageResource(logos[i]);
            sp.addView(logo, new LinearLayout.LayoutParams(dp(20), dp(20)));
            socialTexts[i] = Ui.text(this, 500, 14, 0xFFFFFFFF);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = dp(8);
            sp.addView(socialTexts[i], tlp);
            LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(-2, dp(32));
            plp2.topMargin = dp(8);
            counters.addView(sp, plp2);
            socialPills[i] = sp;
            showCount(i, prefs.getString("count_" + SOCIAL_KEYS[i], null));
        }
        FrameLayout.LayoutParams clp2 = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp2.topMargin = dp(36 + 36 + 2);
        clp2.rightMargin = side;
        content.addView(counters, clp2);

        // focused dock app name
        dockTitle = Ui.text(this, 250, 44, 0xF2FFFFFF);
        dockTitle.setLetterSpacing(-0.01f);
        content.addView(dockTitle, at(W - 2 * side, -2, side + dp(28), bandTop - dp(72)));

        // dock
        dockAdapter = new TileAdapter(dockCell, dockCardW, dockCardH, 0, false);
        dockRv = recycler(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false), dockAdapter);
        content.addView(dockRv, at(dockCell * COLS, dockCardH, side + dp(28) - (dockCell - dockCardW) / 2,
                bandTop + (bandH - dockCardH) / 2));

        // grid
        TextView appsHeader = Ui.text(this, 500, 17, 0xCCFFFFFF);
        appsHeader.setText("Apps");
        appsHeader.setLetterSpacing(0.04f);
        content.addView(appsHeader, at(-2, -2, side + dp(2), gridTop - dp(30)));
        gridAdapter = new TileAdapter(gridCell, gridCardW, gridCardH, rowH - gridCardH, true);
        gridRv = recycler(new GridLayoutManager(this, COLS), gridAdapter);
        content.addView(gridRv, at(gridCell * COLS, rowH, side - (gridCell - gridCardW) / 2, gridTop));

        inputs = new InputsMenu(this);
        root.addView(inputs.panel);

        buildPicker();
        buildFolder(gridCell, gridCardW, gridCardH);
        buildEditMenu();
    }

    private FrameLayout.LayoutParams at(int w, int h, int x, int y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    private RecyclerView recycler(RecyclerView.LayoutManager lm, TileAdapter a) {
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(lm);
        rv.setAdapter(a);
        rv.setItemAnimator(null);
        rv.setClipChildren(false);
        rv.setClipToPadding(false);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setFocusable(false);
        rv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rv.setNestedScrollingEnabled(false);
        return rv;
    }

    private View pill(int icon, String label) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Ui.rounded(0x2EFFFFFF, dp(20), dp(1), 0x33FFFFFF));
        int padH = label == null ? dp(9) : dp(14);
        p.setPadding(padH, 0, padH, 0);
        p.setAlpha(0.8f);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        p.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        if (label != null) {
            TextView t = Ui.text(this, 400, 15, 0xFFFFFFFF);
            t.setText(label);
            t.setMaxWidth(dp(170));
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            p.addView(t, lp);
        }
        return p;
    }

    // ---------------------------------------------------------------- profiles

    private void parseProfiles() {
        // User-added JSON profiles from prefs (AppRepo migrates old config
        // profiles once). No preset user and no fallback dock: an empty list
        // is valid, the home screen still shows the grid and the Settings
        // tile, and the picker tells the user to add someone first.
        profiles.clear();
        for (AppRepo.Profile ap : AppRepo.loadProfiles(this)) {
            Profile p = new Profile();
            p.name = ap.name;
            p.dock.addAll(ap.dock);
            p.kid = ap.kid;
            profiles.add(p);
        }
        lastProfileJson = prefs.getString("user_profiles_json", "");
    }

    /**
     * Profiles changed while Home was away (added, renamed or deleted in
     * Settings / the phone wizard): re-parse, rebuild the picker, and clamp
     * the active profile, so Home does not keep showing a deleted user.
     */
    private void refreshProfilesIfChanged() {
        String now = prefs.getString("user_profiles_json", "");
        if (now.equals(lastProfileJson)) return;
        parseProfiles();
        profileIdx = profiles.isEmpty() ? 0 : Math.max(0, Math.min(profileIdx, profiles.size() - 1));
        if (profileLabel != null) profileLabel.setText(profiles.isEmpty() ? "Add a user" : profiles.get(profileIdx).name);
        if (pickerOverlay != null) root.removeView(pickerOverlay);
        buildPicker();
    }

    /** Full-screen "Who's watching?" overlay, shown on a fresh start and from the profile pill. */
    private void buildPicker() {
        pickerOverlay = new FrameLayout(this);
        pickerOverlay.setBackgroundColor(0xD8000000);
        pickerOverlay.setVisibility(View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        // the focused card scales to 1.12x, so the chain must not clip it:
        // The kid profile card is the tallest (KID badge) and its circle was cut flat at the top
        panel.setClipChildren(false);
        pickerOverlay.setClipChildren(false);
        TextView title = Ui.text(this, 600, 30, 0xFFFFFFFF);
        title.setText(profiles.isEmpty() ? "No users yet" : "Who's watching?");
        panel.addView(title);
        TextView hint = Ui.text(this, 400, 16, 0xCCFFFFFF);
        hint.setSingleLine(false);
        hint.setText(profiles.isEmpty()
                ? "Add a user in Launcher Settings, or on the phone wizard page, then pick them here. Back goes to home."
                : "Pick a profile to load its dock. Back keeps the current one.");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = dp(10);
        panel.addView(hint, hlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setPadding(0, dp(20), 0, dp(20));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = dp(36);
        panel.addView(row, rlp);

        pickerCards.clear();
        for (int i = 0; i < profiles.size(); i++) {
            Profile p = profiles.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setAlpha(0.65f);
            // the card itself scales on focus, so it must not clip its own children;
            // padding also gives the scaled content room inside the card's own bounds
            card.setClipChildren(false);
            card.setPadding(0, dp(12), 0, dp(12));
            FrameLayout circle = new FrameLayout(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(PROFILE_COLORS[i % PROFILE_COLORS.length]);
            circle.setBackground(bg);
            TextView initial = Ui.text(this, 600, 40, 0xFFFFFFFF);
            initial.setGravity(Gravity.CENTER);
            initial.setText(p.name.substring(0, 1).toUpperCase());
            circle.addView(initial, new FrameLayout.LayoutParams(-1, -1));
            card.addView(circle, new LinearLayout.LayoutParams(dp(96), dp(96)));
            TextView name = Ui.text(this, 500, 20, 0xFFFFFFFF);
            name.setGravity(Gravity.CENTER);
            name.setText(p.name);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.topMargin = dp(12);
            card.addView(name, nlp);
            if (p.kid) {
                TextView badge = Ui.text(this, 500, 12, 0xFF000000);
                badge.setText("KID");
                badge.setBackground(Ui.rounded(0xFFFFFFFF, dp(9), 0, 0));
                badge.setPadding(dp(10), dp(2), dp(10), dp(2));
                badge.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
                blp.topMargin = dp(8);
                card.addView(badge, blp);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.leftMargin = dp(20);
            clp.rightMargin = dp(20);
            row.addView(card, clp);
            pickerCards.add(card);
        }

        pickerOverlay.addView(panel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(pickerOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openPicker() {
        // No users yet: skip the empty picker entirely and open the
        // Launcher Settings wizard instead, where a user can actually be
        // added. Back from the wizard returns to the home grid, it does
        // not loop back here, because this only runs on a fresh start or
        // an explicit tap on the profile pill.
        if (profiles.isEmpty()) {
            // First launch only: a guided, step-by-step setup. Once it has
            // run (or been skipped), later empty-profile opens go to the
            // full Settings wizard instead, so this never nags again.
            if (!prefs.getBoolean("first_launch_done", false)) {
                wizardLaunched = true;
                startActivity(new Intent(this, FirstLaunchActivity.class));
            } else {
                startActivity(new Intent(this, WizardActivity.class));
            }
            return;
        }
        pickerIdx = profileIdx;
        pickerOverlay.setVisibility(View.VISIBLE);
        pickerOverlay.bringToFront();
        pickerOpen = true;
        pickerFocus();
    }

    private void closePicker() {
        pickerOverlay.setVisibility(View.GONE);
        pickerOpen = false;
    }

    private void pickerFocus() {
        for (int i = 0; i < pickerCards.size(); i++) {
            View card = pickerCards.get(i);
            boolean f = i == pickerIdx;
            card.animate().scaleX(f ? 1.12f : 1f).scaleY(f ? 1.12f : 1f).setDuration(200).start();
            card.animate().alpha(f ? 1f : 0.65f).setDuration(200).start();
        }
    }

    private void selectProfile(int idx) {
        closePicker();
        if (idx == profileIdx) return;
        profileIdx = idx;
        prefs.edit().putInt("profile", profileIdx).apply();
        profileLabel.setText(profiles.get(profileIdx).name);
        dockIdx = 0;
        zone = Zone.DOCK;
        loadApps();
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        weatherText.setText(weather);
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(h24 ? "H:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE, d MMMM", now));
    }

    // ---------------------------------------------------------------- weather

    /**
     * Weather, via Open-Meteo (free, no key). No city is baked in: the location
     * comes from prefs (set in Settings / the phone wizard), and if none was
     * set it is detected once from the TV's public IP (city level) and saved,
     * so a later manual setting overrides it. Refreshes on resume and every 30 min.
     */
    private final Runnable fetchWeather = () -> {
        main.removeCallbacks(this.fetchWeather);
        // Returning from an app used to fire a request every time, despite
        // the "30 min" comment: skip if a fetch succeeded recently.
        if (lastWeatherAt > 0 && System.currentTimeMillis() - lastWeatherAt < 30 * 60_000L && weather != null) {
            main.postDelayed(this.fetchWeather, 30 * 60_000L - (System.currentTimeMillis() - lastWeatherAt));
            return;
        }
        net.execute(() -> {
            String text = null;
            try {
                String lat = prefs.getString("weather_lat", "");
                String lon = prefs.getString("weather_lon", "");
                if ((lat.isEmpty() || lon.isEmpty()) && System.currentTimeMillis() - weatherIpFailedAt > 24 * 60 * 60_000L) {
                    // A failed lookup used to retry on every resume: now it
                    // waits 24 hours before trying again automatically. A
                    // manual Detect from IP in Settings still works any
                    // time, and clearing a saved location (which was a
                    // successful detection) re-detects on the next refresh.
                    String place = new SetupManager(this).detectWeatherFromIp();
                    if (place != null) {
                        lat = prefs.getString("weather_lat", "");
                        lon = prefs.getString("weather_lon", "");
                    } else {
                        weatherIpFailedAt = System.currentTimeMillis();
                    }
                }
                if (!lat.isEmpty() && !lon.isEmpty()) {
                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code&temperature_unit=fahrenheit"
                            + "&timezone=auto");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    try {
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();
                        JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                        int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                        text = temp + "° " + wmo(cur.getInt("weather_code"));
                    } finally {
                        c.disconnect();
                    }
                }
            } catch (Exception ignored) {
                // offline or API hiccup: keep the last known weather, or blank
            }
            final String t = text;
            main.post(() -> {
                if (t != null) {
                    weather = t;
                    lastWeatherAt = System.currentTimeMillis();
                    prefs.edit().putString("weather", t).apply();
                    weatherText.setText(t);
                }
                // onPause already cancels this, so no focus gate: a pairing
                // prompt or system panel used to kill the timer for good.
                main.postDelayed(this.fetchWeather, 30 * 60_000L);
            });
        });
    };

    /** WMO weather-code to a short word. */
    private static String wmo(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: case 65: return "Rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: return "Snow";
            case 77: return "Snow grains";
            case 80: case 81: case 82: return "Showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunder, hail";
            default: return "";
        }
    }

    // ---------------------------------------------------------------- wallpapers

    private boolean findWallpapers() {
        // bundled wallpapers are copied into the photo store on first run (see Wallpapers)
        wallpapers.clear();
        wallpapers.addAll(Wallpapers.userPhotos(this));
        return !wallpapers.isEmpty();
    }

    /** Decoded at screen size in RGB_565: about 1.8 MB each on a 720p TV. */
    private android.graphics.Bitmap loadWallpaper(Object src) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        decode(src, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        return decode(src, o);
    }

    private android.graphics.Bitmap decode(Object src, android.graphics.BitmapFactory.Options o) {
        if (src instanceof Integer) return android.graphics.BitmapFactory.decodeResource(getResources(), (Integer) src, o);
        return android.graphics.BitmapFactory.decodeFile(((java.io.File) src).getPath(), o);
    }

    /** The phone page added, replaced, deleted or asked to show a photo: update the rotation now. */
    @Override
    public void onWallpapersChanged(java.io.File added) {
        if (wpViews[0] == null) return;
        Object current = wallpapers.isEmpty() ? null : wallpapers.get(Math.min(wpIdx, wallpapers.size() - 1));
        findWallpapers();
        if (wallpapers.isEmpty()) {
            for (ImageView v : wpViews) v.setImageDrawable(null);
            return;
        }
        int at = added != null ? wallpapers.indexOf(added) : wallpapers.indexOf(current);
        if (at < 0) {
            // the photo on screen was deleted: move on to the next one
            wpIdx = Math.max(0, wpIdx - 1) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else if (added != null) {
            wpIdx = (at - 1 + wallpapers.size()) % wallpapers.size();
            main.removeCallbacks(nextWallpaper);
            nextWallpaper.run();
        } else {
            wpIdx = at;
        }
    }

    // ---------------------------------------------------------------- follower counters

    private final Runnable refreshSubs = () -> {
        main.removeCallbacks(this.refreshSubs);
        String[] ids = socialIds.clone();
        net.execute(() -> {
            String[] counts = {
                    ids[0].isEmpty() ? null : SocialCount.youtube(ids[0]),
                    ids[1].isEmpty() ? null : SocialCount.instagram(ids[1]),
                    ids[2].isEmpty() ? null : SocialCount.facebook(ids[2]),
            };
            main.post(() -> {
                boolean allOk = true;
                SharedPreferences.Editor e = prefs.edit();
                for (int i = 0; i < 3; i++) {
                    if (ids[i].isEmpty()) continue;
                    if (counts[i] == null) {
                        allOk = false;   // keep showing the last known number
                        continue;
                    }
                    e.putString("count_" + SOCIAL_KEYS[i], counts[i]);
                    showCount(i, counts[i]);
                }
                if (allOk) e.putLong("counts_at", System.currentTimeMillis());
                e.apply();
                // retry sooner after a failure (e.g. Wi-Fi still connecting after boot)
                // onPause cancels this, so no focus gate (see weather above).
                main.postDelayed(this.refreshSubs, allOk ? SUBS_REFRESH_MS : 5 * 60_000L);
            });
        });
    };

    private void showCount(int i, String count) {
        if (socialPills[i] == null) return;
        socialPills[i].setVisibility(count == null ? View.GONE : View.VISIBLE);
        if (count != null) socialTexts[i].setText(count + " " + SOCIAL_UNITS[i]);
    }

    private void scheduleWallpaper() {
        main.removeCallbacks(nextWallpaper);
        if (wallpapers.size() < 2 || wpViews[0] == null) return;
        int secs = Math.max(5, getResources().getInteger(R.integer.wallpaper_seconds));
        main.postDelayed(nextWallpaper, secs * 1000L);
    }

    /** Fades the next wallpaper in over the current one, then frees the old bitmap. */
    private final Runnable nextWallpaper = () -> {
        if (wallpapers.isEmpty()) return;
        wpIdx = (wpIdx + 1) % wallpapers.size();
        prefs.edit().putInt("wallpaper", wpIdx).apply();
        Object src = wallpapers.get(wpIdx);
        io.execute(() -> {
            android.graphics.Bitmap bmp = loadWallpaper(src);
            if (bmp == null) {
                // One bad image used to stop rotation for good: keep going.
                main.post(this::scheduleWallpaper);
                return;
            }
            main.post(() -> {
                ImageView front = wpViews[wpFront], back = wpViews[1 - wpFront];
                back.setImageBitmap(bmp);
                back.animate().alpha(1f).setDuration(2000).start();
                front.animate().alpha(0f).setDuration(2000).withEndAction(() -> front.setImageDrawable(null)).start();
                wpFront = 1 - wpFront;
                scheduleWallpaper();
            });
        });
    };

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String text = "Offline";
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni != null && ni.isConnected()) {
            if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                text = "Ethernet";
            } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                text = "Wi-Fi";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                String ssid = wi == null ? null : unquote(wi.getSSID());
                if ((ssid == null || ssid.equals("<unknown ssid>")) && wi != null) {
                    try {
                        List<WifiConfiguration> cfgs = wm.getConfiguredNetworks();
                        if (cfgs != null) for (WifiConfiguration cfg : cfgs) {
                            if (cfg.networkId == wi.getNetworkId()) ssid = unquote(cfg.SSID);
                        }
                    } catch (SecurityException ignored) {
                    }
                }
                if (ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>")) text = ssid;
            }
        }
        wifiText.setText(text);
    }

    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
        if (!nav) return super.dispatchKeyEvent(e);
        // OK/enter is special: it supports hold-to-jiggle, so it activates on release
        boolean center = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (center) return handleCenter(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;

        if (pickerOpen) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && pickerIdx > 0) { pickerIdx--; pickerFocus(); }
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && pickerIdx < pickerCards.size() - 1) { pickerIdx++; pickerFocus(); }
            else if (k == KeyEvent.KEYCODE_BACK) closePicker();
            return true;
        }
        if (editMenuOpen) {
            if (k == KeyEvent.KEYCODE_DPAD_UP && editMenuIdx > 0) { editMenuIdx--; renderEditMenu(); }
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN && editMenuIdx < editMenuItems.size() - 1) { editMenuIdx++; renderEditMenu(); }
            else if (k == KeyEvent.KEYCODE_BACK) closeEditMenu();
            return true;
        }
        if (folderOpen) {
            int n = folderAdapter.getItemCount();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT && folderIdx > 0) folderIdx--;
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT && folderIdx < n - 1) folderIdx++;
            else if (k == KeyEvent.KEYCODE_DPAD_UP && folderIdx >= COLS) folderIdx -= COLS;
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN && folderIdx + COLS < n) folderIdx += COLS;
            else if (k == KeyEvent.KEYCODE_BACK) closeFolder();
            else return true;
            folderAdapter.setFocused(folderRv, folderIdx);
            folderRv.scrollToPosition(folderIdx);
            return true;
        }
        if (inputs.isOpen()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) inputs.move(-1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) inputs.move(1);
            else inputs.close();   // back, left, right
            return true;
        }
        if (editMode) {
            if (k == KeyEvent.KEYCODE_BACK) { exitEditMode(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT) { moveIcon(-1); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT) { moveIcon(1); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_UP && zone == Zone.GRID) { moveIcon(-COLS); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && zone == Zone.GRID) { moveIcon(COLS); return true; }
            return true;
        }

        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_BACK:
                if (zone != Zone.DOCK && dockAdapter.getItemCount() > 0) {
                    zone = Zone.DOCK;
                    applyFocus(true);
                }
                break;
            default: break;
        }
        return true;
    }

    /**
     * OK/enter: short press activates on release; holding it ~2s on an app icon opens
     * the edit menu (Rearrange / Move to dock or grid / Hide / Delete / Done).
     * A framework long-press flag counts as a hold too (adb --longpress).
     */
    private boolean handleCenter(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                centerDownAt = SystemClock.uptimeMillis();
                centerHeld = false;
                if ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                    centerHeld = true;
                    startHold();
                }
            } else if (centerDownAt > 0 && !centerHeld
                    && (SystemClock.uptimeMillis() - centerDownAt >= 2000
                        || (e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0)) {
                // a flagged repeat is a long press by the system's own definition,
                // even if it arrives before the 2s repeat timeout
                centerHeld = true;
                startHold();
            }
            return true;
        }
        // ACTION_UP
        if (ignoreCenterUp) {
            ignoreCenterUp = false;
            return true;
        }
        if (centerHeld) {
            centerHeld = false;
            return true;
        }
        if (centerDownAt <= 0) return true;
        centerDownAt = 0;
        if (pickerOpen) {
            selectProfile(pickerIdx);
            return true;
        }
        if (inputs.isOpen()) {
            Intent i = inputs.selected();
            inputs.close();
            if (i != null) launch(i);
            return true;
        }
        if (editMenuOpen) {
            editMenuSelect();
            return true;
        }
        if (folderOpen) {
            AppRepo.App a = folderAppAt();
            if (a != null) launch(AppRepo.launchIntent(getPackageManager(), a));
            return true;
        }
        if (editMode) {
            AppRepo.App a = focusedApp();
            if (a != null) openEditMenuFor(a, false);
            return true;
        }
        activate();
        return true;
    }

    /** A hold began: on an icon it opens the edit menu directly; Rearrange enters jiggle. */
    private void startHold() {
        if (folderOpen) {
            AppRepo.App a = folderAppAt();
            if (a != null) {
                openEditMenuFor(a, true);
                ignoreCenterUp = true;
            }
        } else if (!pickerOpen && !inputs.isOpen() && !editMenuOpen && !editMode
                && (zone == Zone.DOCK || zone == Zone.GRID)) {
            AppRepo.App a = focusedApp();
            if (a != null && !FOLDER_PKG.equals(a.pkg) && !SETTINGS_PKG.equals(a.pkg)) {
                openEditMenuFor(a, false);
                ignoreCenterUp = true;
            }
        }
    }

    private AppRepo.App focusedApp() {
        if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) return dockAdapter.apps.get(dockIdx);
        if (zone == Zone.GRID && gridIdx < gridAdapter.getItemCount()) return gridAdapter.apps.get(gridIdx);
        return null;
    }

    private AppRepo.App folderAppAt() {
        if (folderIdx < folderAdapter.getItemCount()) return folderAdapter.apps.get(folderIdx);
        return null;
    }

    // ---------------------------------------------------------------- rearrange

    private void enterEditMode() {
        editMode = true;
        startJiggle();
    }

    private void exitEditMode() {
        editMode = false;
        stopJiggle();
        saveOrders();
    }

    private void saveOrders() {
        List<String> d = new ArrayList<>();
        for (AppRepo.App a : dockAdapter.apps) d.add(a.pkg);
        AppRepo.saveDock(this, profileIdx, d);
        StringBuilder g = new StringBuilder();
        for (AppRepo.App a : gridAdapter.apps) {
            if (FOLDER_PKG.equals(a.pkg) || SETTINGS_PKG.equals(a.pkg)) continue;
            if (g.length() > 0) g.append(',');
            g.append(a.pkg);
        }
        prefs.edit().putString("grid_order", g.toString()).apply();
    }

    /** Swap the focused icon with its neighbour; the folder tile itself never moves. */
    private void moveIcon(int delta) {
        if (zone == Zone.DOCK) {
            int n = dockAdapter.getItemCount();
            int to = dockIdx + delta;
            if (to < 0 || to >= n) return;
            Collections.swap(dockAdapter.apps, dockIdx, to);
            dockIdx = to;
            dockAdapter.notifyDataSetChanged();
            dockAdapter.setFocused(dockRv, dockIdx);
            dockRv.post(() -> {
                applyFocus(false);
                startJiggle();
            });
        } else if (zone == Zone.GRID) {
            int n = gridAdapter.getItemCount();
            int to = gridIdx + delta;
            if (to < 0 || to >= n) return;
            if (FOLDER_PKG.equals(gridAdapter.apps.get(gridIdx).pkg)) return;
            if (FOLDER_PKG.equals(gridAdapter.apps.get(to).pkg)) return;
            if (SETTINGS_PKG.equals(gridAdapter.apps.get(gridIdx).pkg)) return;
            if (SETTINGS_PKG.equals(gridAdapter.apps.get(to).pkg)) return;
            Collections.swap(gridAdapter.apps, gridIdx, to);
            gridIdx = to;
            gridAdapter.notifyDataSetChanged();
            gridAdapter.setFocused(gridRv, gridIdx);
            gridRv.post(() -> {
                applyFocus(false);
                startJiggle();
            });
        }
    }

    private void startJiggle() {
        stopJiggle();
        RecyclerView rv = zone == Zone.DOCK ? dockRv : gridRv;
        int pos = zone == Zone.DOCK ? dockIdx : gridIdx;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (!(vh instanceof TileAdapter.Holder)) return;
        jiggleCard = ((TileAdapter.Holder) vh).card;
        jiggleAnim = ObjectAnimator.ofFloat(jiggleCard, "rotation", -2.5f, 2.5f);
        jiggleAnim.setDuration(140);
        jiggleAnim.setRepeatMode(ValueAnimator.REVERSE);
        jiggleAnim.setRepeatCount(ValueAnimator.INFINITE);
        jiggleAnim.start();
    }

    private void stopJiggle() {
        if (jiggleAnim != null) {
            jiggleAnim.cancel();
            jiggleAnim = null;
        }
        if (jiggleCard != null) {
            jiggleCard.setRotation(0f);
            jiggleCard = null;
        }
    }

    // ---------------------------------------------------------------- Hidden folder

    private void buildFolder(int cellW, int cardW, int cardH) {
        folderOverlay = new FrameLayout(this);
        folderOverlay.setBackgroundColor(0xFF000000);
        folderOverlay.setVisibility(View.GONE);

        TextView title = Ui.text(this, 600, 30, 0xFFFFFFFF);
        title.setText("Hidden");
        folderOverlay.addView(title, at(-2, -2, side, dp(34)));
        TextView hint = Ui.text(this, 400, 16, 0xCCFFFFFF);
        hint.setText("Hold OK on an app to unhide it. Back goes home.");
        folderOverlay.addView(hint, at(-2, -2, side, dp(78)));

        folderEmpty = Ui.text(this, 400, 18, 0xCCFFFFFF);
        folderEmpty.setText("Nothing hidden yet.\nHold OK on any app, then Move to Hidden folder.");
        folderEmpty.setGravity(Gravity.CENTER);
        folderEmpty.setSingleLine(false);
        folderOverlay.addView(folderEmpty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        folderAdapter = new TileAdapter(cellW, cardW, cardH, rowH - cardH, true);
        folderRv = recycler(new GridLayoutManager(this, COLS), folderAdapter);
        folderOverlay.addView(folderRv, at(cellW * COLS, rowH, side - (cellW - cardW) / 2, dp(150)));
        root.addView(folderOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openFolder() {
        folderOpen = true;
        folderIdx = 0;
        refreshFolder();
        folderOverlay.setVisibility(View.VISIBLE);
        folderOverlay.bringToFront();
    }

    private void closeFolder() {
        folderOverlay.setVisibility(View.GONE);
        folderOpen = false;
    }

    private void refreshFolder() {
        io.execute(() -> {
            List<AppRepo.App> all = AppRepo.allApps(getApplicationContext());
            Set<String> hidden = AppRepo.hiddenPkgs(this, profileIdx);
            List<AppRepo.App> list = new ArrayList<>();
            for (AppRepo.App a : all) if (hidden.contains(a.pkg)) list.add(a);
            main.post(() -> {
                folderAdapter.setApps(list);
                folderEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                int rows = (list.size() + COLS - 1) / COLS;
                ViewGroup.LayoutParams lp = folderRv.getLayoutParams();
                // Cap the folder height to the screen so a long hidden list
                // actually scrolls to the focused tile instead of running
                // off the bottom of the screen where it can never be seen.
                lp.height = Math.min(Math.max(rows, 1) * rowH, H - dp(170));
                folderRv.setLayoutParams(lp);
                folderIdx = Math.max(0, Math.min(folderIdx, list.size() - 1));
                folderAdapter.setFocused(folderRv, list.isEmpty() ? -1 : folderIdx);
            });
        });
    }

    // ---------------------------------------------------------------- icon menu

    private void buildEditMenu() {
        editMenuOverlay = new FrameLayout(this);
        editMenuOverlay.setBackgroundColor(0xB8000000);
        editMenuOverlay.setVisibility(View.GONE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.rounded(0xFF1E1E21, dp(20), dp(1), 0x33FFFFFF));
        panel.setPadding(dp(28), dp(24), dp(28), dp(24));
        editMenuTitle = Ui.text(this, 600, 22, 0xFFFFFFFF);
        panel.addView(editMenuTitle);
        editMenuList = new LinearLayout(this);
        editMenuList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(16);
        panel.addView(editMenuList, llp);

        editMenuOverlay.addView(panel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(editMenuOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void openEditMenuFor(AppRepo.App app, boolean forHidden) {
        editMenuApp = app;
        editMenuTitle.setText(app.label);
        editMenuList.removeAllViews();
        editMenuItems.clear();
        editMenuActions.clear();
        List<String> labels = new ArrayList<>();
        if (!FOLDER_PKG.equals(app.pkg)) {
            // only offered when not already jiggling; in jiggle, OK already opened this menu
            if (!forHidden && !editMode) {
                labels.add("Rearrange");
                editMenuActions.add("rearrange");
            }
            if (forHidden) {
                labels.add("Unhide");
                editMenuActions.add("unhide");
            } else {
                // dock <-> grid moves; a full dock offers no Move to dock
                if (zone == Zone.DOCK) {
                    labels.add("Move to grid");
                    editMenuActions.add("togrid");
                } else if (dockAdapter.getItemCount() < COLS) {
                    labels.add("Move to dock");
                    editMenuActions.add("todock");
                }
                labels.add("Move to Hidden folder");
                editMenuActions.add("hide");
            }
            // system uninstall screen: Android asks to confirm, and for system apps
            // it may only offer uninstalling updates or disabling, not a full removal
            labels.add("Delete app");
            editMenuActions.add("delete");
        }
        labels.add("Done");
        editMenuActions.add("done");
        for (String label : labels) {
            TextView item = Ui.text(this, 500, 18, 0xFFFFFFFF);
            item.setText(label);
            item.setAlpha(0.55f);
            item.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(10);
            editMenuList.addView(item, lp);
            editMenuItems.add(item);
        }
        editMenuIdx = 0;
        renderEditMenu();
        editMenuOverlay.setVisibility(View.VISIBLE);
        editMenuOverlay.bringToFront();
        editMenuOpen = true;
    }

    private void renderEditMenu() {
        for (int i = 0; i < editMenuItems.size(); i++) {
            View v = editMenuItems.get(i);
            boolean f = i == editMenuIdx;
            // Selected row gets a real blue background, not just scale/alpha:
            // scale alone was unreadable from the couch.
            v.setBackground(f ? Ui.rounded(0xFF0A84FF, dp(10), 0, 0) : null);
            v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(150).start();
            v.animate().alpha(f ? 1f : 0.55f).setDuration(150).start();
        }
    }

    private void closeEditMenu() {
        editMenuOverlay.setVisibility(View.GONE);
        editMenuOpen = false;
    }

    private void editMenuSelect() {
        if (editMenuIdx < 0 || editMenuIdx >= editMenuActions.size()) {
            closeEditMenu();
            return;
        }
        String action = editMenuActions.get(editMenuIdx);
        AppRepo.App app = editMenuApp;
        closeEditMenu();
        if (app == null || "done".equals(action)) return;
        switch (action) {
            case "rearrange": {
                enterEditMode();
                break;
            }
            case "delete": {
                if (editMode) exitEditMode();
                // ACTION_DELETE shows Android's own confirm screen; the launcher
                // never deletes anything silently. PACKAGE_REMOVED reloads the list.
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + app.pkg)));
                } catch (Exception ignored) {
                }
                break;
            }
            case "unhide": {
                Set<String> hidden = AppRepo.hiddenPkgs(this, profileIdx);
                hidden.remove(app.pkg);
                AppRepo.saveHidden(this, profileIdx, hidden);
                refreshFolder();
                loadApps();
                break;
            }
            case "hide": {
                exitEditMode();   // saves the order with the app still placed, so unhide restores it
                Set<String> hidden = AppRepo.hiddenPkgs(this, profileIdx);
                hidden.add(app.pkg);
                AppRepo.saveHidden(this, profileIdx, hidden);
                loadApps();
                break;
            }
            case "todock": {
                exitEditMode();
                List<String> dockOrder = AppRepo.effectiveDock(this, profileIdx);
                if (!dockOrder.contains(app.pkg)) dockOrder.add(app.pkg);
                saveDockOrder(dockOrder);
                loadApps();
                break;
            }
            case "togrid": {
                exitEditMode();
                List<String> dockOrder = AppRepo.effectiveDock(this, profileIdx);
                dockOrder.remove(app.pkg);
                saveDockOrder(dockOrder);
                loadApps();
                break;
            }
        }
    }

    private void saveDockOrder(List<String> order) {
        AppRepo.saveDock(this, profileIdx, order);
    }

    private void up() {
        if (zone == Zone.DOCK) {
            zone = Zone.TOP;
        } else if (zone == Zone.GRID) {
            if (gridIdx >= COLS) gridIdx -= COLS;
            else if (dockAdapter.getItemCount() > 0) {
                zone = Zone.DOCK;
                dockIdx = Math.min(gridIdx, dockAdapter.getItemCount() - 1);
            } else zone = Zone.TOP;
        } else return;
        applyFocus(true);
    }

    private void down() {
        int ng = gridAdapter.getItemCount();
        if (zone == Zone.TOP) {
            if (dockAdapter.getItemCount() > 0) zone = Zone.DOCK;
            else if (ng > 0) zone = Zone.GRID;
            else return;
        } else if (zone == Zone.DOCK) {
            if (ng == 0) return;
            zone = Zone.GRID;
            gridIdx = Math.min(dockIdx, ng - 1);
        } else {
            if (gridIdx + COLS < ng) gridIdx += COLS;
            else if (gridIdx / COLS < (ng - 1) / COLS) gridIdx = ng - 1;
            else return;
        }
        applyFocus(true);
    }

    private void left() {
        if (zone == Zone.TOP && topIdx > 0) topIdx--;
        else if (zone == Zone.DOCK && dockIdx > 0) dockIdx--;
        else if (zone == Zone.GRID && gridIdx % COLS > 0) gridIdx--;
        else return;
        applyFocus(true);
    }

    private void right() {
        if (zone == Zone.TOP && topIdx < PILLS - 1) topIdx++;
        else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount() - 1) dockIdx++;
        else if (zone == Zone.GRID && gridIdx % COLS < COLS - 1 && gridIdx + 1 < gridAdapter.getItemCount()) gridIdx++;
        else return;
        applyFocus(true);
    }

    private void activate() {
        if (zone == Zone.TOP) {
            if (topIdx == 0) {
                int[] loc = new int[2];
                pills[0].getLocationInWindow(loc);
                inputs.open(loc[0], loc[1] + pills[0].getHeight() + dp(8));
            } else if (topIdx == 1) {
                launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } else if (topIdx == 2) {
                launch(new Intent(Settings.ACTION_SETTINGS));
            } else {
                openPicker();
            }
        } else if (zone == Zone.DOCK && dockIdx < dockAdapter.getItemCount()) {
            launch(AppRepo.launchIntent(getPackageManager(), dockAdapter.apps.get(dockIdx)));
        } else if (zone == Zone.GRID && gridIdx < gridAdapter.getItemCount()) {
            AppRepo.App a = gridAdapter.apps.get(gridIdx);
            if (FOLDER_PKG.equals(a.pkg)) openFolder();
            else if (SETTINGS_PKG.equals(a.pkg)) startActivity(new Intent(this, WizardActivity.class));
            else launch(AppRepo.launchIntent(getPackageManager(), a));
        }
    }

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- focus rendering

    private void applyFocus(boolean animate) {
        prefs.edit().putInt("zone", zone.ordinal()).putInt("top", topIdx)
                .putInt("dock", dockIdx).putInt("grid", gridIdx).apply();

        dockAdapter.setFocused(dockRv, zone == Zone.DOCK ? dockIdx : -1);
        gridAdapter.setFocused(gridRv, zone == Zone.GRID ? gridIdx : -1);
        for (int i = 0; i < PILLS; i++) {
            float a = zone == Zone.TOP && i == topIdx ? 1f : 0.8f;
            pills[i].animate().alpha(a).setDuration(150).start();
        }

        // scroll: dock/top at rest, grid rows bring the grid up one row at a time
        float shift = 0;
        if (zone == Zone.GRID) {
            int row = gridIdx / COLS;
            shift = gridTop - dp(78) + Math.max(0, row - 1) * rowH;
        }
        if (animate) content.animate().translationY(-shift).setDuration(380)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        else content.setTranslationY(-shift);

        // dock title + glow follow the dock selection
        if (dockAdapter.getItemCount() > 0 && dockIdx != lastTitleApp) {
            lastTitleApp = dockIdx;
            AppRepo.App a = dockAdapter.apps.get(dockIdx);
            if (animate) {
                titleChanging = true;
                dockTitle.animate().alpha(0f).setDuration(90).withEndAction(() -> {
                    dockTitle.setText(a.label);
                    titleChanging = false;
                    dockTitle.animate().alpha(titleAlpha()).setDuration(200).start();
                }).start();
            } else {
                dockTitle.setText(a.label);
                dockTitle.setAlpha(titleAlpha());
            }
            setGlow(a.glow, animate);
        } else if (!titleChanging) {
            dockTitle.animate().alpha(titleAlpha()).setDuration(200).start();
        }
        glow.animate().alpha(zone == Zone.GRID ? 0f : zone == Zone.TOP ? 0.6f : 1f).setDuration(300).start();

        RectF target = targetRect();
        if (target != null) moveHighlight(target, animate && hlPlaced);
    }

    private float titleAlpha() {
        return zone == Zone.TOP ? 0.55f : 1f;
    }

    private RectF targetRect() {
        if (zone == Zone.TOP) {
            View p = pills[topIdx];
            float x = pillRow.getLeft() + p.getLeft(), y = pillRow.getTop() + p.getTop();
            if (p.getWidth() == 0) return null;
            float o = dp(3);
            hlRadius = p.getHeight() / 2f + o;
            return new RectF(x - o, y - o, x + p.getWidth() + o, y + p.getHeight() + o);
        }
        RecyclerView rv = zone == Zone.DOCK ? dockRv : gridRv;
        int pos = zone == Zone.DOCK ? dockIdx : gridIdx;
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh == null) return null;
        View card = vh.itemView.findViewById(R.id.card);
        float cx = rv.getLeft() + vh.itemView.getLeft() + card.getLeft() + card.getWidth() / 2f;
        float cy = rv.getTop() + vh.itemView.getTop() + card.getTop() + card.getHeight() / 2f;
        float hw = card.getWidth() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        float hh = card.getHeight() * TileAdapter.FOCUS_SCALE / 2f + dp(5);
        hlRadius = dp(15);
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private void moveHighlight(RectF to, boolean animate) {
        if (hlAnim != null) hlAnim.cancel();
        final RectF from = new RectF(hl);
        final float r0 = hlRadiusNow, r1 = hlRadius;
        if (!animate) {
            hl.set(to);
            placeHighlight(hl, r1);
            highlight.animate().alpha(1f).setDuration(200).start();
            hlPlaced = true;
            return;
        }
        hlAnim = ValueAnimator.ofFloat(0f, 1f);
        hlAnim.setDuration(340);
        hlAnim.setInterpolator(new OvershootInterpolator(1.15f));
        hlAnim.addUpdateListener(va -> {
            float t = (float) va.getAnimatedValue();
            hl.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                    from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t);
            placeHighlight(hl, r0 + (r1 - r0) * Math.min(1f, t));
        });
        hlAnim.start();
    }

    private void placeHighlight(RectF r, float radius) {
        hlRadiusNow = radius;
        highlight.set(r, radius);
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        int from = glowColor == 0 ? (color & 0x00FFFFFF) : glowColor;
        glowColor = color;
        if (!animate) {
            paintGlow(color);
            return;
        }
        glowAnim = ValueAnimator.ofArgb(from, color);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(va -> paintGlow((int) va.getAnimatedValue()));
        glowAnim.start();
    }

    private void paintGlow(int c) {
        int rgb = c & 0x00FFFFFF;
        int a = (c >>> 24) * 0x5C / 0xFF;
        glowBg.setColors(new int[]{(a << 24) | rgb, ((a / 3) << 24) | rgb, rgb});
    }
}
