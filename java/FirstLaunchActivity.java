package com.stevi2k.atvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.nayuki.qrcodegen.QrCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guided first-launch setup, shown only once, only when the app is first
 * opened with no users yet. One question per screen, D-pad only, no
 * typing: a first user is created as "User 1" (rename it later on the
 * phone wizard page), then a dock is picked, then junk apps and the home
 * screen are offered as simple Yes / No choices. Back goes one step
 * back, or skips setup entirely from the welcome screen. Every exit
 * marks setup done, so this never nags again; Settings has a
 * "First-launch setup again" item to re-run it on purpose.
 */
public class FirstLaunchActivity extends Activity {

    private SetupManager setup;
    private LocalShell shell;
    private ScrollView scrollView;
    private LinearLayout list;
    private TextView titleView, subView, resultView;
    // Phone-page QR, shown ONLY on the empty Load screen. Every show()
    // call hides it by default; the empty case shows it after show().
    private LinearLayout qrBox;
    private ImageView qrView;
    private TextView qrUrlView;
    private TextView qrCaption;
    // Phone page URL computed for the empty Load screen (null when no
    // TV IP could be found); reused by the QR screen.
    private String phonePageUrl;
    private final List<View> itemViews = new ArrayList<>();
    private final List<TextView> titleViews = new ArrayList<>();
    private final List<TextView> descViews = new ArrayList<>();
    private final List<Item> currentItems = new ArrayList<>();
    private int selectedIdx;
    private int step; // 0 welcome, 1 who, 2 dock, 3 junk, 4 home, 5 done
    // Load-configuration screens are NOT normal steps: step stays 0 while
    // they are shown, so onBackPressed must handle them explicitly.
    // 0 = normal step flow, 1 = load list, 2 = load confirm,
    // 3 = load user select, 4 = load location, 5 = load debloat,
    // 6 = load home, 7 = load done, 8 = phone page info (a sub-screen
    // of the empty load list; Back from it returns to the load list),
    // 9 = phone page QR screen (Back returns to the load list),
    // 10 = load overlay-permission step (Back returns to load junk).
    // loadBranch is true from the load
    // list onward, so showJunk/showHome/showDone know to route back
    // through the load pages instead of the normal step flow.
    private int loadState;
    private boolean loadBranch;
    private java.io.File loadFile;
    private java.io.File loadParsedFile;
    private List<AppRepo.Profile> loadProfiles;
    private final Set<Integer> loadSelected = new HashSet<>();
    private String loadActiveName = "";
    private String loadWeatherLat = "";
    private String loadWeatherLon = "";
    private String loadWeatherPlace = "";
    private boolean loadWeatherPresent;
    private final List<String> loadAppliedNames = new ArrayList<>();
    private int userIdx = -1;
    private String userName = "";
    private boolean pendingKid;
    private final Set<String> dockPicked = new java.util.LinkedHashSet<>();
    private String junkResult = "";
    private boolean junkRunning = false;
    private boolean junkAbandoned = false;
    private android.os.Handler junkHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable junkTimeout = null;
    private String homeResult = "";
    private boolean homeRunning = false;
    private boolean homeAbandoned = false;

    private static final class Item {
        final String label, desc;
        final Runnable run;
        Item(String label, String desc, Runnable run) { this.label = label; this.desc = desc; this.run = run; }
        Item(String label, Runnable run) { this(label, "", run); }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setup = new SetupManager(this);
        shell = new LocalShell(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);

        titleView = Ui.text(this, 700, 32, Color.WHITE);
        subView = Ui.text(this, 400, 17, 0xCCFFFFFF);
        subView.setSingleLine(false);
        resultView = Ui.text(this, 400, 15, 0xFFFFD60A);
        resultView.setSingleLine(false);

        scrollView = new ScrollView(this);
        scrollView.setFocusable(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(list, new ViewGroup.LayoutParams(-1, -2));

        qrBox = new LinearLayout(this);
        qrBox.setOrientation(LinearLayout.VERTICAL);
        qrBox.setGravity(Gravity.CENTER_HORIZONTAL);
        qrBox.setVisibility(View.GONE);
        qrView = new ImageView(this);
        int qrPx = Ui.dp(this, 300);
        qrView.setLayoutParams(new LinearLayout.LayoutParams(qrPx, qrPx));
        qrBox.addView(qrView);
        qrUrlView = Ui.text(this, 700, 17, Color.WHITE);
        qrUrlView.setSingleLine(false);
        qrUrlView.setGravity(Gravity.CENTER);
        qrUrlView.setPadding(0, Ui.dp(this, 8), 0, 0);
        qrBox.addView(qrUrlView);
        qrCaption = Ui.text(this, 400, 15, 0xCCFFFFFF);
        qrCaption.setSingleLine(false);
        qrCaption.setGravity(Gravity.CENTER);
        qrCaption.setText("Scan with your phone to open the phone page.");
        qrCaption.setPadding(0, Ui.dp(this, 8), 0, 0);
        qrBox.addView(qrCaption);

        root.addView(titleView);
        root.addView(subView);
        root.addView(resultView);
        root.addView(qrBox);
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = Ui.text(this, 400, 15, 0x99FFFFFF);
        hint.setSingleLine(false);
        hint.setText("Up / Down to move  •  OK to select  •  Back to go back a step");
        hint.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(hint);
        root.setFocusable(true);
        setContentView(root);
        root.requestFocus();
        step = 0;
        showStep();
    }

    private void markDone() {
        getSharedPreferences("home", MODE_PRIVATE).edit()
                .putBoolean("first_launch_done", true).apply();
    }

    private void finishDone() {
        markDone();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (junkRunning) { junkAbandoned = true; junkRunning = false; if (junkTimeout != null) junkHandler.removeCallbacks(junkTimeout); }
        if (homeRunning) { homeAbandoned = true; homeRunning = false; }
        if (loadState == 9) { showHowToUpload(); return; }
        if (loadState == 8) { showLoadList(); return; }
        if (loadState == 10) { showLoadJunk(); return; }
        if (loadState == 7) { showLoadHome(); return; }
        if (loadState == 6) {
            // Home Back skips the overlay step when Display over apps
            // is already allowed, mirroring advanceAfterJunk(): go
            // back to junk (loadState 5) instead of the overlay step.
            if (android.provider.Settings.canDrawOverlays(this)) showLoadJunk(); else showLoadOverlay();
            return;
        }
        if (loadState == 5) { showLoadAdb(); return; }
        if (loadState == 11) { showLoadLocation(); return; }
        if (loadState == 4) { showLoadUsers(); return; }
        if (loadState == 3) { if (loadFile != null) showLoadConfirm(loadFile); else showLoadList(); return; }
        if (loadState == 2) { showLoadList(); return; }
        if (loadState == 1) { loadState = 0; showWelcome(); return; }
        if (step == 0) {
            // Welcome is the floor of the wizard: Back stays here, it never
            // exits to the launcher. Setup only ends via Done.
            return;
        } else if (!loadBranch && step == 7 && android.provider.Settings.canDrawOverlays(this)) {
            // Home Back skips the overlay step when Display over apps
            // is already allowed, mirroring advanceAfterJunk(): step 7
            // is home, step 5 is junk, step 6 is the overlay step.
            step = 5;
            showStep();
        } else {
            step--;
            showStep();
        }
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && !itemViews.isEmpty()) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
                selectedIdx = Math.min(selectedIdx + 1, itemViews.size() - 1);
                renderSelection();
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                selectedIdx = Math.max(selectedIdx - 1, 0);
                renderSelection();
                return true;
            }
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                    || e.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                Item it = currentItems.get(selectedIdx);
                if (it != null) it.run.run();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private GradientDrawable cardBg(boolean selected) {
        return Ui.glass(this, selected);
    }

    private void renderSelection() {
        for (int i = 0; i < itemViews.size(); i++) {
            boolean sel = i == selectedIdx;
            itemViews.get(i).setBackground(cardBg(sel));
            if (i < titleViews.size()) titleViews.get(i).setTextColor(sel ? Color.BLACK : Color.WHITE);
            if (i < descViews.size() && descViews.get(i) != null) descViews.get(i).setTextColor(sel ? 0xCC000000 : 0xCCFFFFFF);
        }
        if (!itemViews.isEmpty()) {
            View v = itemViews.get(selectedIdx);
            scrollView.post(() -> scrollView.smoothScrollTo(0, v.getTop()));
        }
    }

    private void show(String title, String sub, List<Item> items) {
        titleView.setText(title);
        subView.setText(sub);
        resultView.setText("");
        // QR is empty-case only: hidden on every screen by default.
        if (qrBox != null) qrBox.setVisibility(View.GONE);
        if (qrView != null) qrView.setImageBitmap(null);
        if (qrUrlView != null) qrUrlView.setText("");
        list.removeAllViews();
        itemViews.clear();
        titleViews.clear();
        descViews.clear();
        currentItems.clear();
        currentItems.addAll(items);
        for (Item it : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
            TextView tv = Ui.text(this, 700, 19, Color.WHITE);
            tv.setSingleLine(false);
            tv.setText(it.label);
            card.addView(tv);
            titleViews.add(tv);
            if (it.desc != null && !it.desc.isEmpty()) {
                TextView dv = Ui.text(this, 400, 15, 0xCCFFFFFF);
                dv.setSingleLine(false);
                dv.setText(it.desc);
                dv.setPadding(0, Ui.dp(this, 4), 0, 0);
                card.addView(dv);
                descViews.add(dv);
            } else {
                descViews.add(null);
            }
            card.setFocusable(false);
            card.setFocusableInTouchMode(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 12);
            card.setLayoutParams(lp);
            card.setOnClickListener(v -> it.run.run());
            list.addView(card);
            itemViews.add(card);
        }
        selectedIdx = 0;
        renderSelection();
        scrollView.post(() -> scrollView.scrollTo(0, 0));
    }

    private void showStep() {
        switch (step) {
            case 0: showWelcome(); break;
            case 1: showWho(); break;
            case 2: showName(); break;
            case 3: showDock(); break;
            case 4: showAdb(); break;
            case 5: showJunk(); break;
            case 6: showOverlay(); break;
            case 7: showHome(); break;
            default: showDone(); break;
        }
    }

    private void showWelcome() {
        loadState = 0;
        loadBranch = false;
        loadFile = null;
        loadParsedFile = null;
        loadProfiles = null;
        loadSelected.clear();
        loadAppliedNames.clear();
        List<Item> items = new ArrayList<>();
        items.add(new Item("Start setup", "5 easy steps: a user, a name, a home row, junk apps, and the home screen.", () -> { step = 1; showStep(); }));
        items.add(new Item("Load a saved configuration", "Pick which users to add from a config file already on this TV, then confirm location, junk apps, and the home screen.", this::showLoadList));
        show("Welcome to Home", "Step 1 of 6  •  Set up in a minute, on the TV.", items);
    }

    private String configDate(java.io.File f) {
        return new java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US)
                .format(new java.util.Date(f.lastModified()));
    }

    // TV local IPv4, same approach as WizardActivity.tvIp(): WifiManager
    // first (ACCESS_WIFI_STATE is already in the manifest, normal
    // permission, no runtime prompt), then NetworkInterface enumeration.
    // Returns null when no address can be found, never a placeholder.
    private String tvIp() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
            }
        } catch (Exception ignored) {
        }
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses(); addrs.hasMoreElements();) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // Renders a local, offline QR code for the exact phone page URL
    // (http://<ip>:8080, no path, no token) into qrView and shows qrBox.
    // On any failure the QR stays hidden and only the URL text is used;
    // a blank or placeholder QR is never shown.
    private void showPhoneQr(String url) {
        try {
            QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
            int border = 4;
            int modules = qr.size;
            int total = modules + border * 2;
            int targetPx = Ui.dp(this, 300);
            int scale = Math.max(1, targetPx / total);
            int sizePx = total * scale;
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < sizePx; y++) {
                for (int x = 0; x < sizePx; x++) {
                    int mx = x / scale - border;
                    int my = y / scale - border;
                    boolean dark = mx >= 0 && mx < modules && my >= 0 && my < modules && qr.getModule(mx, my);
                    bmp.setPixel(x, y, dark ? Color.BLACK : Color.WHITE);
                }
            }
            qrView.setImageBitmap(bmp);
            qrUrlView.setText(url);
            qrBox.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
            qrBox.setVisibility(View.GONE);
        }
    }

    private void showLoadList() {
        loadState = 1;
        loadBranch = true;
        loadFile = null;
        List<java.io.File> files = SetupManager.configFiles(this);
        if (files.isEmpty()) {
            List<Item> items = new ArrayList<>();
            items.add(new Item("Refresh", "Check again for uploaded config files.", this::refreshLoadList));
            items.add(new Item("How to upload configuration", "Steps to get a config file onto this TV from your phone.", this::showHowToUpload));
            show("No saved configurations", "Upload a config file from the phone page, then run first-launch setup again.", items);
            // The QR is on demand only (Step 3 of the How screen): never
            // shown automatically here, so the Refresh row is not pushed
            // off screen. The sub text carries no URL; the address lives
            // on the QR screen.
            String ip = tvIp();
            phonePageUrl = ip != null ? "http://" + ip + ":8080" : null;
            return;
        }
        List<Item> items = new ArrayList<>();
        for (java.io.File f : files) {
            final java.io.File file = f;
            long kb = Math.max(1, f.length() / 1024);
            items.add(new Item(file.getName(), configDate(file) + "  ·  " + kb + " KB", () -> showLoadConfirm(file)));
        }
        // Non-empty list: the file rows alone left no way to refresh
        // or upload another config, so append Refresh and How to upload
        // configuration after them, in that order. Remote Back from the
        // How screen returns here (showLoadList), showing this list
        // again while files exist.
        items.add(new Item("Refresh", "Check again for uploaded config files.", this::refreshLoadList));
        items.add(new Item("How to upload configuration", "Steps to get a config file onto this TV from your phone.", this::showHowToUpload));
        show("Load a configuration", "Pick a file already on this TV. Back returns to Welcome.", items);
    }

    // Refresh action used by the Refresh row on both the empty Load
    // screen and the non-empty Load list. It re-reads the config
    // files fresh, reports what it found as a Toast, then shows the
    // result: the normal list screen when files exist, or the empty
    // screen when nothing was found (showLoadList() lands on the
    // empty screen, so a list-screen Refresh that finds nothing does
    // not leave a stale list on screen).
    private void refreshLoadList() {
        List<java.io.File> files = SetupManager.configFiles(this);
        if (files.isEmpty()) {
            Toast.makeText(this, "No config files found. Upload one from the phone page, then tap Refresh again.", Toast.LENGTH_LONG).show();
            showLoadList();
            return;
        }
        int n = files.size();
        Toast.makeText(this, "Found " + n + (n == 1 ? " config file." : " config files."), Toast.LENGTH_LONG).show();
        showLoadList();
    }

    // "How to upload configuration" screen, replacing the old Phone page
    // info screen. Four steps in order; the QR code and the Display over
    // apps permission both live inside this screen (Steps 3 and 2), not
    // as top-level rows on the empty Load screen. No Back row: remote
    // Back (loadState 8) returns to the empty Load screen, not to
    // Welcome. Step 2 opens system settings without changing this
    // screen, so Back from settings returns here. Steps 1 and 4 are
    // informational and navigate nowhere.
    private void showHowToUpload() {
        loadState = 8;
        loadBranch = true;
        List<Item> items = new ArrayList<>();
        items.add(new Item("Step 1: Make sure you're on the same network as the TV", "If it's not working, check your VPN and Wi-Fi.", () -> {}));
        items.add(new Item("Step 2: Allow Display over apps", "Click to open settings > find the Home app > allow Display over apps.", this::openOverlaySettings));
        items.add(new Item("Step 3: Open the website on your phone", "Pair your device to the TV with the link or QR code.", this::showPhoneQrScreen));
        items.add(new Item("Step 4: Head to the Config tab", "Upload in the Config tab, then refresh on the TV.", () -> {}));
        show("How to upload configuration", "Follow these steps to upload a config file from your phone.", items);
    }

    // Dedicated QR screen, opened only from Step 3 of the "How to upload
    // configuration" screen. show() hides the QR box by default, so it
    // is shown here only after show(). No Back row: remote Back
    // (loadState 9) returns to the How screen, not to the empty Load
    // screen or Welcome. If no IP is found, no QR is shown (existing
    // fallback handling).
    private void showPhoneQrScreen() {
        loadState = 9;
        loadBranch = true;
        List<Item> items = new ArrayList<>();
        String ip = tvIp();
        if (ip != null) {
            String url = "http://" + ip + ":8080";
            phonePageUrl = url;
            show("Phone page QR code", "Open this page on your phone, on the same Wi-Fi.", items);
            showPhoneQr(url);
        } else {
            phonePageUrl = null;
            show("Phone page QR code", "Upload a config file from the phone page on this TV, then run first-launch setup again.", items);
        }
    }

    private void showLoadConfirm(java.io.File file) {
        loadState = 2;
        loadBranch = true;
        loadFile = file;
        String summary;
        try {
            String text = SetupManager.readConfigFile(file);
            String err = SetupManager.validateConfigText(text);
            if (err != null) {
                Toast.makeText(this, "Cannot load: " + err, Toast.LENGTH_LONG).show();
                showLoadList();
                return;
            }
            org.json.JSONObject root = new org.json.JSONObject(text);
            summary = SetupManager.describeConfig(root);
            // Parse once here and keep the profiles in memory for the
            // user-select and location pages. Do not re-read the file on
            // every toggle. If this is the same file as before (Back from
            // user-select), keep the existing selection instead of
            // resetting it to all-selected.
            if (loadParsedFile == null || !loadParsedFile.equals(file)) {
                loadProfiles = parseLoadProfiles(root);
                loadActiveName = root.optString("activeProfileName", "");
                org.json.JSONObject w = root.optJSONObject("weather");
                loadWeatherLat = w == null ? "" : w.optString("lat", "");
                loadWeatherLon = w == null ? "" : w.optString("lon", "");
                loadWeatherPlace = w == null ? "" : w.optString("place", "");
                loadWeatherPresent = false;
                if (!loadWeatherLat.isEmpty() && !loadWeatherLon.isEmpty()) {
                    try {
                        Double.parseDouble(loadWeatherLat);
                        Double.parseDouble(loadWeatherLon);
                        loadWeatherPresent = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
                loadSelected.clear();
                for (int i = 0; i < loadProfiles.size(); i++) loadSelected.add(i);
                loadParsedFile = file;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Cannot read that file.", Toast.LENGTH_LONG).show();
            showLoadList();
            return;
        }
        List<Item> items = new ArrayList<>();
        items.add(new Item("Load this configuration", "Once loaded, you pick what transfers.", this::showLoadUsers));
        items.add(new Item("Go back", "Does not load the configuration.", this::showLoadList));
        show("Load " + file.getName() + "?", "Confirm below. Back returns to the list.", items);
        resultView.setText(summary);
    }

    private List<AppRepo.Profile> parseLoadProfiles(org.json.JSONObject root) {
        List<AppRepo.Profile> out = new ArrayList<>();
        org.json.JSONArray profiles = root.optJSONArray("profiles");
        if (profiles == null) return out;
        for (int i = 0; i < profiles.length(); i++) {
            org.json.JSONObject o = profiles.optJSONObject(i);
            if (o == null) continue;
            AppRepo.Profile p = new AppRepo.Profile();
            p.id = o.optString("id", "");
            if (p.id.isEmpty()) p.id = java.util.UUID.randomUUID().toString();
            p.name = o.optString("name", "").trim();
            p.kid = o.optBoolean("kid", false);
            org.json.JSONArray dock = o.optJSONArray("dock");
            if (dock != null) for (int j = 0; j < dock.length(); j++) p.dock.add(dock.optString(j));
            org.json.JSONArray hidden = o.optJSONArray("hidden");
            if (hidden != null) for (int j = 0; j < hidden.length(); j++) p.hidden.add(hidden.optString(j));
            out.add(p);
        }
        return out;
    }

    private void updateLoadCount() {
        resultView.setText("Currently importing [" + loadSelected.size() + "] users");
    }

    private void showLoadUsers() {
        if (loadProfiles == null || loadProfiles.isEmpty() || loadFile == null) {
            Toast.makeText(this, "No users in this file.", Toast.LENGTH_LONG).show();
            showLoadList();
            return;
        }
        loadState = 3;
        loadBranch = true;
        List<Item> items = new ArrayList<>();
        items.add(new Item("Next", "Select which users you want to import.", () -> {
            if (loadSelected.isEmpty()) {
                Toast.makeText(this, "Pick at least one user", Toast.LENGTH_SHORT).show();
                return;
            }
            if (applyLoadUsers()) showLoadLocation();
        }));
        for (int i = 0; i < loadProfiles.size(); i++) {
            final int idx = i;
            AppRepo.Profile p = loadProfiles.get(i);
            items.add(new Item((loadSelected.contains(idx) ? "[✓] " : "[ ] ") + p.name,
                    p.dock.size() + " apps on dock, " + p.hidden.size() + " hidden", () -> {
                if (loadSelected.contains(idx)) loadSelected.remove(idx);
                else loadSelected.add(idx);
                if (selectedIdx < titleViews.size()) {
                    titleViews.get(selectedIdx).setText((loadSelected.contains(idx) ? "[✓] " : "[ ] ") + loadProfiles.get(idx).name);
                }
                updateLoadCount();
                renderSelection();
            }));
        }
        show("What users do you want to add?", "Pick the users to import from this file. Back returns to the confirm screen.", items);
        updateLoadCount();
    }

    /** Applies ONLY the selected file profiles, merged into the TV's
     * existing profiles. Same-name profiles are replaced, others are
     * appended, unselected file profiles are not added, and existing TV
     * profiles not in the file are left alone. No backup file is written;
     * on any failure nothing is saved and the load list is shown again.
     * Returns true on success. */
    private boolean applyLoadUsers() {
        try {
            String text = SetupManager.readConfigFile(loadFile);
            String err = SetupManager.validateConfigText(text);
            if (err != null) {
                Toast.makeText(this, "Cannot load: " + err, Toast.LENGTH_LONG).show();
                showLoadList();
                return false;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Cannot read that file.", Toast.LENGTH_LONG).show();
            showLoadList();
            return false;
        }
        List<AppRepo.Profile> merged = new ArrayList<>(AppRepo.loadProfiles(this));
        loadAppliedNames.clear();
        List<Integer> selected = new ArrayList<>(loadSelected);
        java.util.Collections.sort(selected);
        String firstSelectedName = "";
        for (int idx : selected) {
            AppRepo.Profile p = loadProfiles.get(idx);
            if (firstSelectedName.isEmpty()) firstSelectedName = p.name;
            loadAppliedNames.add(p.name);
            int existing = -1;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).name.equals(p.name)) { existing = i; break; }
            }
            if (existing >= 0) merged.set(existing, p);
            else merged.add(p);
        }
        AppRepo.saveProfiles(this, merged);
        String target = "";
        for (String name : loadAppliedNames) {
            if (name.equals(loadActiveName)) { target = name; break; }
        }
        if (target.isEmpty()) target = firstSelectedName;
        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        for (int i = 0; i < merged.size(); i++) {
            if (merged.get(i).name.equals(target)) { prefs.edit().putInt("profile", i).apply(); break; }
        }
        Toast.makeText(this, "Loaded.", Toast.LENGTH_LONG).show();
        return true;
    }

    private void showLoadLocation() {
        loadState = 4;
        loadBranch = true;
        List<Item> items = new ArrayList<>();
        items.add(new Item("Yes, use this location", "Set home weather to this location.", () -> {
            if (loadWeatherPresent) {
                getSharedPreferences("home", MODE_PRIVATE).edit()
                        .putString("weather_lat", loadWeatherLat)
                        .putString("weather_lon", loadWeatherLon)
                        .putString("weather_place", loadWeatherPlace).apply();
            }
            showLoadAdb();
        }));
        items.add(new Item("No, skip location", "Leave the weather exactly as it was.", this::showLoadAdb));
        show("Use this location?", "The config file includes a weather location. Back returns to the user list.", items);
        resultView.setText(loadWeatherPlace.isEmpty() ? "No location in this file." : loadWeatherPlace);
    }

    private void showLoadAdb() {
        loadState = 11;
        loadBranch = true;
        showAdb();
    }

    private void showLoadJunk() {
        loadState = 5;
        loadBranch = true;
        showJunk();
    }

    private void showLoadHome() {
        loadState = 6;
        loadBranch = true;
        showHome();
    }

    private void showLoadOverlay() {
        loadState = 10;
        loadBranch = true;
        showOverlay();
    }

    private String joinLoadNames() {
        if (loadAppliedNames.isEmpty()) return "";
        if (loadAppliedNames.size() == 1) return loadAppliedNames.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < loadAppliedNames.size(); i++) {
            if (i > 0) sb.append(i == loadAppliedNames.size() - 1 ? " and " : ", ");
            sb.append(loadAppliedNames.get(i));
        }
        return sb.toString();
    }

    private void showLoadDone() {
        loadState = 7;
        loadBranch = true;
        List<Item> items = new ArrayList<>();
        items.add(new Item("Go to home", "Finish setup and see your home screen.", () -> {
            SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
            List<AppRepo.Profile> ps = AppRepo.loadProfiles(this);
            String target = "";
            for (String name : loadAppliedNames) {
                if (name.equals(loadActiveName)) { target = name; break; }
            }
            if (target.isEmpty() && !loadAppliedNames.isEmpty()) target = loadAppliedNames.get(0);
            for (int i = 0; i < ps.size(); i++) {
                if (ps.get(i).name.equals(target)) { prefs.edit().putInt("profile", i).apply(); break; }
            }
            finishDone();
        }));
        items.add(new Item("Open Settings", "The full wizard: more users, hidden apps, weather, restore, and more.", () -> {
            finishDone();
            startActivity(new Intent(this, WizardActivity.class));
        }));
        String sub = "All done. " + joinLoadNames() + " loaded, with their docks and hidden apps.";
        show("You're set up", sub, items);
    }

    private void showWho() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Adult", "Next you type a name for this user, right here on the TV.", () -> {
            pendingKid = false;
            step = 2; showStep();
        }));
        items.add(new Item("Kid", "Same as Adult, but marked as a kid profile.", () -> {
            pendingKid = true;
            step = 2; showStep();
        }));
        show("Who is this for?", "Step 2 of 6  •  Next step asks for a name.", items);
    }


    private void showName() {
        // Typing happens in TextEntryActivity, with the on-screen
        // keyboard, so this wizard keeps its simple D-pad list.
        // Cancelling the text entry goes back to Who, not forward.
        Intent i = new Intent(this, TextEntryActivity.class);
        i.putExtra("title", "What is their name?");
        i.putExtra("hint", "Type a name, then Save. Back cancels and goes back to Who.");
        i.putExtra("initial", "User " + (setup.profiles().size() + 1));
        i.putExtra("capWords", true);
        startActivityForResult(i, 201);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 201) return;
        if (resultCode != RESULT_OK || data == null) {
            step = 1;
            showStep();
            return;
        }
        String n = data.getStringExtra("text");
        if (n != null) n = n.trim();
        String def = "User " + (setup.profiles().size() + 1);
        if (n == null || n.isEmpty()) n = def;
        if (userIdx >= 0) {
            // Back from the dock: update the user already created, do not make a second one.
            setup.renameProfile(userIdx, n);
            userName = n;
        } else {
            userIdx = setup.addProfile(n, pendingKid);
            userName = userIdx >= 0 ? setup.profiles().get(userIdx).name : n;
        }
        step = 3;
        showStep();
    }

    private void showDock() {
        List<AppRepo.App> apps = AppRepo.allAppsLabelsOnly(this);
        // Do not clear dockPicked here: Back from Junk to Dock must keep
        // the picks already ticked, and LinkedHashSet keeps tick order.
        List<Item> items = new ArrayList<>();
        items.add(new Item("Continue", "Your dock picks already saved as you ticked them. This just moves to the next step.", () -> {
            step = 4; showStep();
        }));
        for (AppRepo.App a : apps) {
            items.add(new Item((dockPicked.contains(a.pkg) ? "[✓] " : "[ ] ") + a.label, a.pkg, () -> {
                if (dockPicked.contains(a.pkg)) {
                    dockPicked.remove(a.pkg);
                } else if (dockPicked.size() < 5) {
                    dockPicked.add(a.pkg);
                } else {
                    Toast.makeText(this, "Dock holds 5 apps", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (selectedIdx < titleViews.size()) {
                    titleViews.get(selectedIdx).setText((dockPicked.contains(a.pkg) ? "[✓] " : "[ ] ") + a.label);
                }
                setup.saveDock(userIdx, new ArrayList<>(dockPicked));
                renderSelection();
            }));
        }
        show("Pick your home row", "Step 4 of 6  •  " + userName + "  •  OK to tick an app, up to 5. Picks save as you tap, then Continue.", items);
    }

    // Forward from junk to the next step, skipping the overlay step
    // when Display over apps is already allowed (the user already
    // toggled it, so showing the overlay screen again after junk —
    // even its allowed branch with a Continue row — is pointless).
    // Load branch: home is loadState 6, overlay is loadState 10.
    // Normal branch: home is step 7, overlay is step 6, junk is
    // step 5, ADB is step 4 (see showStep). When not allowed yet, the
    // overlay step is shown exactly as before, and showOverlay()
    // itself is unchanged as the fallback for any path that still
    // reaches it.
    private void advanceAfterJunk() {
        if (loadBranch) {
            if (android.provider.Settings.canDrawOverlays(this)) showLoadHome(); else showLoadOverlay();
        } else {
            if (android.provider.Settings.canDrawOverlays(this)) { step = 7; showStep(); } else { step = 6; showStep(); }
        }
    }

    // "Enable ADB" step, in BOTH branches, before junk. Junk removal
    // and set-home both run over the TV's own localhost ADB
    // (LocalShell, 127.0.0.1:5555), so if Developer options /
    // network debugging is off, or "Allow USB debugging?" (tick
    // Always allow) was never accepted for this keypair, those steps
    // hang on Working. This step is deliberately never auto-skipped,
    // even when ADB is already connected: Check is one tap when
    // it is already on. shell.ready() is only run from the Check row,
    // on a background thread, never on step entry, so entering this
    // step can never block on a connection attempt.
    // Normal branch: step 4, forward to junk (step 5), Back to Dock
    // (step 3, via the generic step--). Load branch: loadState 11,
    // forward to junk (loadState 5), Back to location (loadState 4).
    private void showAdb() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Open developer settings", "Open Developer options on this TV.", this::openDeveloperSettings));
        items.add(new Item("Check ADB connection", "Tests the TV's local debugging connection.", () -> {
            resultView.setText("Checking the TV's local debugging connection...");
            new Thread(() -> {
                final boolean ok = shell.ready();
                final String reason = ok ? null : shell.lastError();
                runOnUiThread(() -> {
                    if (ok) {
                        if (loadBranch) showLoadJunk(); else { step = 5; showStep(); }
                    } else {
                        Toast.makeText(this, "ADB not connected yet", Toast.LENGTH_LONG).show();
                        String steps = "1. If Developer options is not in Settings yet: Settings > Device Preferences > About > Build, tap Build 7 times. 2. In Developer options, turn on network debugging (not just Developer options itself). 3. When the TV asks Allow USB debugging?, tick Always allow and press OK. 4. Tap Check ADB connection below.";
                        if (reason != null && !reason.isEmpty()) {
                            resultView.setText("ADB is not connected yet: " + reason + ". " + steps);
                        } else {
                            resultView.setText("ADB is not connected yet. " + steps);
                        }
                    }
                });
            }).start();
        }));
        show("Enable ADB", "The next steps need the TV's local debugging connection to remove junk apps and set home. If it is off, those steps hang on Working.", items);
        resultView.setText("1. If Developer options is not in Settings yet: Settings > Device Preferences > About > Build, tap Build 7 times. 2. In Developer options, turn on network debugging (not just Developer options itself). 3. When the TV asks Allow USB debugging?, tick Always allow and press OK. 4. Tap Check ADB connection below.");
    }

    // Same logic as WizardActivity.openDeveloperSettings(): the
    // developer-settings intent does nothing on some TVs when
    // Developer options has not been unlocked yet, so fall back to
    // plain Settings with the Build 7 times Toast. Tapping the row
    // does not change the wizard screen, so Back from system
    // settings returns to the ADB step.
    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                Toast.makeText(this, "Developer options not unlocked yet: Settings > Device Preferences > About > Build, tap Build 7 times", Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Toast.makeText(this, "Could not open Settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showJunk() {
        List<SetupManager.Candidate> cands = setup.installedCandidates();
        List<String> onByDefault = new ArrayList<>();
        for (SetupManager.Candidate c : cands) if (c.onByDefault) onByDefault.add(c.pkg);
        List<Item> items = new ArrayList<>();
        items.add(new Item("Yes, remove junk apps", "Recommended. Turns off " + onByDefault.size() + " known junk apps and services. Nothing is deleted, Restore in Settings brings them back.", () -> {
            junkRunning = true;
            junkAbandoned = false;
            // Working screen: the old screen just said Working and never
            // advanced if the local ADB connection hung (e.g. the TV's
            // "Allow USB debugging?" prompt was never accepted, or network
            // debugging is off), so the user was stuck with no error and
            // no way forward. Now there is a timeout message and a
            // Continue anyway button, and the debloat result (including
            // per-app failures) is shown on the next screen.
            List<Item> working = new ArrayList<>();
            working.add(new Item("Continue anyway", "Skip ahead without waiting for the junk removal. You can run it again later in Settings, after checking Helper status.", () -> {
                junkAbandoned = true;
                junkRunning = false;
                if (junkTimeout != null) junkHandler.removeCallbacks(junkTimeout);
                advanceAfterJunk();
            }));
            show("Removing junk apps...", "Working, this takes a few seconds... If it stalls, it is usually the TV waiting for \"Allow USB debugging?\" (tick Always allow) or network debugging being off.", working);
            resultView.setText("Working...");
            junkTimeout = () -> {
                if (!junkRunning) return;
                resultView.setText("Still working after 45 seconds. This usually means the TV is waiting for \"Allow USB debugging?\" (tick Always allow), or network debugging is off. Press Continue anyway, then check Settings > Helper status, accept the prompt, and run the junk removal again from Settings.");
            };
            junkHandler.postDelayed(junkTimeout, 45_000);
            new Thread(() -> {
                String r;
                try {
                    String backupPath = setup.backup();
                    r = "Backup: " + backupPath + "\n" + setup.debloat(shell, onByDefault, true);
                } catch (Exception e) {
                    r = "Junk removal failed: " + String.valueOf(e.getMessage());
                }
                final String res = r;
                runOnUiThread(() -> {
                    junkResult = res;
                    junkRunning = false;
                    if (junkTimeout != null) junkHandler.removeCallbacks(junkTimeout);
                    if (!junkAbandoned) advanceAfterJunk();
                });
            }).start();
        }));
        items.add(new Item("No, keep everything", "Skip this. You can remove junk apps later in Settings.", () -> advanceAfterJunk()));
        show("Remove junk apps?", "Step 5 of 6  •  A backup runs first, so this is reversible." + (loadBranch ? " This is the standard junk list, not the disabled apps from the config file." : ""), items);
    }

    // Opens the exact system page for this app's overlay permission:
    // Settings > Apps > Home > Display over other apps. The pairing
    // prompt is a TYPE_APPLICATION_OVERLAY window (PairingPrompt), so
    // without this toggle no phone can pair or upload a config, even
    // though SYSTEM_ALERT_WINDOW is declared in the manifest. This is
    // deliberately not granted programmatically and not via helper ADB
    // (the helper is unreliable on fresh installs): the user toggles
    // Allow, then Back returns to whatever wizard screen launched this.
    // No auto-advance on resume: the wizard stays on the overlay step
    // until the user taps "Check again".
    // On this Sony Google TV the overlay-permission intent does not
    // resolve (the TV's Settings app has no activity for it), which
    // used to end in a failure toast.
    // Fall back in order: the App info page for Home (where the
    // Display over other apps row lives, reached manually via
    // Settings > Apps > See all apps > Home), then plain Settings
    // with the exact manual path as a toast, then a short failure
    // toast only if all three fail. Back from any of these returns
    // to whatever wizard screen launched this.
    private void openOverlaySettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:com.stevi2k.atvhome")));
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:com.stevi2k.atvhome")));
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            Toast.makeText(this, "Display over apps is in Settings > Apps > See all apps > Home > Display over other apps > Allow", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
            Toast.makeText(this, "Could not open Settings", Toast.LENGTH_SHORT).show();
        }
    }

    // Phone-pairing-permission step, in BOTH wizard branches, between
    // junk and home. Normal branch: reached as step 6 (showStep case 6),
    // forward to step 7 (home); Back is the generic step--, so Back
    // returns to junk, and Back from home skips this step when
    // already allowed (see onBackPressed). Load branch: reached via
    // showLoadOverlay with loadState 10, forward to showLoadHome;
    // Back (loadState 10) returns to showLoadJunk. Forwarding is
    // always via loadBranch, so the two branches stay consistent.
    private void showOverlay() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            List<Item> items = new ArrayList<>();
            items.add(new Item("Continue", "A phone can now ask to pair with this TV.", () -> {
                if (loadBranch) showLoadHome(); else { step = 7; showStep(); }
            }));
            show("Allow Display over apps", "Display over other apps is on for this launcher, so a phone can now ask to pair.", items);
            return;
        }
        List<Item> items = new ArrayList<>();
        items.add(new Item("Open Display over other apps settings", "Opens Settings for this app, where you turn Allow on, then Back returns here.", this::openOverlaySettings));
        items.add(new Item("Check again", "Re-check whether Display over other apps is now allowed.", () -> {
            if (android.provider.Settings.canDrawOverlays(this)) {
                if (loadBranch) showLoadHome(); else { step = 7; showStep(); }
            } else {
                Toast.makeText(this, "Still not allowed", Toast.LENGTH_SHORT).show();
            }
        }));
        show("Allow Display over apps", "The TV cannot show the \"Allow this phone?\" prompt until Display over other apps is on. Without it, no phone can pair or upload a config.", items);
    }

    private void showHome() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Yes, make Home my home screen", "Pressing Home will open this launcher from now on. Reversible in Settings, Restore.", () -> {
            homeRunning = true; homeAbandoned = false;
            resultView.setText("Working...");
            new Thread(() -> {
                String target = null;
                for (Map<String, String> h : setup.homePackages()) {
                    if ("com.google.android.apps.tv.launcherx".equals(h.get("pkg"))) { target = h.get("pkg"); break; }
                    if (target == null) target = h.get("pkg");
                }
                homeResult = target == null ? "No stock home app found to disable." : setup.setHome(shell, target);
                runOnUiThread(() -> { homeRunning = false; if (!homeAbandoned) { if (loadBranch) showLoadDone(); else { step = 8; showStep(); } } });
            }).start();
        }));
        items.add(new Item("No, keep the stock home", "Open Home from the Apps tab whenever you want it. You can switch later in Settings.", () -> { if (loadBranch) showLoadDone(); else { step = 8; showStep(); } }));
        String sub = "Step 6 of 6";
        if (!junkResult.isEmpty()) {
            int done = 0, failed = 0;
            for (String line : junkResult.split("\n")) {
                if (line.contains(": disabled")) done++;
                else if (line.contains("failed") || line.contains("skipped")) failed++;
            }
            sub += "  •  Junk apps: " + done + " turned off";
            if (failed > 0) sub += ", " + failed + " failed or skipped (see Settings, Restore to undo, or run it again)";
            else sub += ", see Settings, Restore to undo.";
            // Show the first failure lines in full, so a stall is not silent.
            StringBuilder fb = new StringBuilder();
            for (String line : junkResult.split("\n")) {
                if (line.contains("failed") || line.contains("Junk removal failed")) { fb.append(line).append("\n"); if (fb.length() > 300) break; }
            }
            if (fb.length() > 0) sub += "\n" + fb.toString().trim();
        }
        show("Make Home your home screen?", sub, items);
    }

    private void showDone() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Go to home", "Finish setup and see your home screen.", () -> {
            SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
            if (userIdx >= 0) prefs.edit().putInt("profile", userIdx).apply();
            finishDone();
        }));
        items.add(new Item("Open Settings", "The full wizard: more users, hidden apps, weather, restore, and more.", () -> {
            finishDone();
            startActivity(new Intent(this, WizardActivity.class));
        }));
        StringBuilder sub = new StringBuilder("All done. ");
        sub.append(userName).append(" was created");
        sub.append(", with ").append(dockPicked.size()).append(" dock app(s). ");
        sub.append("Rename a user on the phone wizard page, or add more users in Settings.");
        show("You're set up", sub.toString(), items);
    }
}
