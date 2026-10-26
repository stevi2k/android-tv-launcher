package com.stevi2k.atvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.util.TypedValue;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Launcher Settings, opened from the Settings tile in the Apps grid.
 * D-pad only: Up/Down move, OK selects, Back goes to the previous screen.
 *
 * This is the TV side of the one-install setup wizard. The phone side is
 * the wizard webpage hosted by PhoneServer, both use SetupManager, and
 * debloat / set-home go through LocalShell, the TV's own localhost ADB.
 * Renaming users is easiest on the phone webpage, the TV screen here
 * covers backup, debloat, docks, hidden, set-home and restore.
 */
public class WizardActivity extends Activity {

    private SetupManager setup;
    private LocalShell shell;
    private ScrollView scrollView;
    private LinearLayout list;
    private TextView titleView, subView, resultView;
    private ImageView glyphView;
    private TextView glyphDesc;
    private final List<TextView> descViews = new ArrayList<>();
    private final List<TextView> chevronViews = new ArrayList<>();
    private Runnable backAction = null;
    private final java.util.Map<String, Integer> savedIndex = new java.util.HashMap<>();
    private String currentTitle = "";
    private int helperToken = 0;
    private boolean helperDone = false;
    private java.io.File configRenameFile = null;
    // Manual D-pad navigation: the old version relied on Android focus, but the
    // buttons had a solid background with no focus highlight, so moving with
    // the remote looked like nothing happened and the list did not scroll.
    // Now the activity owns Up/Down/OK, the selected row is bright blue, and
    // the ScrollView scrolls to it. Buttons are not focusable, so Android
    // focus can never steal the keys away.
    private final List<View> itemViews = new ArrayList<>();
    private final List<TextView> titleViews = new ArrayList<>();
    private final List<android.widget.ImageView> iconViews = new ArrayList<>();
    private final List<Boolean> appIconFlags = new ArrayList<>();
    private final List<Item> currentItems = new ArrayList<>();
    private int selectedIdx;
    private int textIdx = -1;
    private boolean textKid;
    private String textQuery = "";

    private static final class Item {
        final String label, desc;
        final Runnable run;
        int iconRes = 0;
        android.graphics.drawable.Drawable appIcon = null;
        int trailIcon1 = 0, trailIcon2 = 0;
        boolean noChevron = false;
        Runnable trailRun1 = null, trailRun2 = null;
        Item(String label, Runnable run) { this.label = label; this.desc = ""; this.run = run; }
        Item(String label, String desc, Runnable run) { this.label = label; this.desc = desc; this.run = run; }
        Item(String label, String desc, Runnable run, int iconRes) { this.label = label; this.desc = desc; this.run = run; this.iconRes = iconRes; }
        Item(String label, Runnable run, int iconRes) { this.label = label; this.desc = ""; this.run = run; this.iconRes = iconRes; }
        Item(String label, String desc, Runnable run, android.graphics.drawable.Drawable appIcon) { this.label = label; this.desc = desc; this.run = run; this.appIcon = appIcon; }
        Item withTrails(int i1, Runnable r1, int i2, Runnable r2) { trailIcon1 = i1; trailRun1 = r1; trailIcon2 = i2; trailRun2 = r2; return this; }
        Item noArrow() { noChevron = true; return this; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setup = new SetupManager(this);
        shell = new LocalShell(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = Ui.dp(this, 16);
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
        list.setPadding(0, 0, 0, Ui.dp(this, 32));
        scrollView.addView(list, new ViewGroup.LayoutParams(-1, -2));

        root.addView(titleView);
        subView.setVisibility(android.view.View.GONE);
        root.addView(subView);
        root.addView(resultView);
        // Apple TV Settings style: one full-width list, no left glyph panel.
        // Each row is icon + label + description, focused row is white with
        // dark text, unfocused rows are plain. Back returns one level, as
        // on tvOS. The glyph panel from v12-v14 is gone on purpose.
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = Ui.text(this, 400, 15, 0x99FFFFFF);
        hint.setSingleLine(false);
        hint.setText("Up / Down to move  •  OK to select  •  Back to go back");
        hint.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(hint);
        root.setFocusable(true);
        setContentView(root);
        root.requestFocus();
        showMenu();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            // Cancelled text entry: stay where the user was.
            if (requestCode == 101 || requestCode == 102) showUsers();
            else if (requestCode == 103) showWeather();
            else if (requestCode == 104) showAllAppsDebloat();
            else if (requestCode == 105) showExport();
            return;
        }
        String text = data.getStringExtra("text");
        if (text != null) text = text.trim();
        if (requestCode == 101) {
            // Add a user, with a typed name and the kid choice made first.
            if (text != null && !text.isEmpty()) {
                int idx = setup.addProfile(text, textKid);
                Toast.makeText(this, "User added", Toast.LENGTH_SHORT).show();
                if (idx >= 0) showDockPick(idx); else showUsers();
            } else showUsers();
        } else if (requestCode == 102) {
            if (textIdx >= 0 && text != null && !text.isEmpty()) {
                setup.renameProfile(textIdx, text);
                Toast.makeText(this, "Renamed", Toast.LENGTH_SHORT).show();
            }
            showUsers();
        } else if (requestCode == 103) {
            final String loc = text;
            if (loc == null || loc.isEmpty()) { showWeather(); return; }
            new Thread(() -> {
                String place = setup.setWeatherFromText(loc);
                runOnUiThread(() -> {
                    showWeather();
                    resultView.setText(place == null ? "Place not found, try another spelling." : "Weather set to " + place);
                });
            }).start();
        } else if (requestCode == 104) {
            textQuery = text == null ? "" : text;
            showAllAppsDebloat();
        } else if (requestCode == 105) {
            if (configRenameFile != null && text != null && !text.isEmpty()) {
                String err = SetupManager.renameConfigFile(this, configRenameFile.getName(), text);
                Toast.makeText(this, err == null ? "Renamed" : err, Toast.LENGTH_SHORT).show();
            }
            configRenameFile = null;
            showExport();
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
        // Apple TV Settings rows are full-width, not rounded pills: white
        // when focused, transparent otherwise, with a thin divider feel
        // from the row padding below.
        GradientDrawable d = new GradientDrawable();
        d.setColor(selected ? 0xFFFFFFFF : 0x00000000);
        d.setCornerRadius(0);
        return d;
    }

    private int glyphFor(String text) {
        if (text == null) return R.drawable.mdi_cog;
        if ("Pick a user".equals(currentTitle)) {
            if (text.toLowerCase(java.util.Locale.US).contains("(kid)")) return R.drawable.mdi_account_child;
            return R.drawable.mdi_account;
        }
        String s = text.toLowerCase(java.util.Locale.US);
        if (s.contains("import")) return R.drawable.mdi_download;
        if (s.contains("export")) return R.drawable.mdi_upload;
        if (s.contains("configuration")) return R.drawable.ic_settings;
        if (s.contains("backup")) return R.drawable.mdi_file_restore;
        if (s.contains("unpair")) return R.drawable.mdi_cellphone_off;
        if (s.contains("helper")) return R.drawable.mdi_signal_cellular_3;
        if (s.contains("check again")) return R.drawable.mdi_refresh;
        if (s.contains("update")) return R.drawable.mdi_download;
        if (s.contains("developer")) return R.drawable.ic_settings;
        if (s.contains("restore")) return R.drawable.mdi_backup_restore;
        if (s.contains("disable") || s.contains("debloat") || s.contains("junk")) return R.drawable.mdi_apps;
        if (s.contains("delete")) return R.drawable.mdi_delete;
        if (s.contains("rename")) return R.drawable.mdi_pencil;
        if (s.equals("adult")) return R.drawable.mdi_account;
        if (s.equals("kid")) return R.drawable.mdi_account_child;
        if (s.contains("(kid)")) return R.drawable.mdi_account_child;
        if (s.contains("(adult)")) return R.drawable.mdi_account;
        if (s.contains("add a user") || s.contains("add user")) return R.drawable.mdi_account_plus;
        if (s.contains("toggle kid")) return R.drawable.mdi_account_switch;
        if (s.contains("user") || s.contains("profile")) return R.drawable.mdi_account_group;
        if (s.contains("weather")) return R.drawable.mdi_weather_partly_cloudy;
        if (s.contains("dock")) return R.drawable.mdi_dock_bottom;
        if (s.contains("hidden")) return R.drawable.mdi_eye_off;
        if (s.contains("set this launcher") || s.contains("set as home")) return R.drawable.mdi_home;
        if (s.contains("home")) return R.drawable.mdi_home;
        if (s.contains("tv info")) return R.drawable.mdi_wifi_marker;
        if (s.contains("how to pair")) return R.drawable.mdi_cellphone_link;
        if (s.contains("what does")) return R.drawable.mdi_alert_circle;
        if (s.equals("done")) return R.drawable.mdi_exit_to_app;
        if (s.contains("ip address")) return R.drawable.mdi_ip_network;
        if (s.contains("phone remote")) return R.drawable.mdi_cellphone_link;
        if (s.contains("phone wizard")) return R.drawable.mdi_cellphone_information;
        if (s.contains("current user")) return R.drawable.mdi_account_circle;
        if (s.contains("wifi network")) return R.drawable.mdi_wifi_cog;
        if (s.contains("wifi")) return R.drawable.mdi_wifi;
        if (s.contains("tv info") || s.contains("ip address")) return R.drawable.mdi_information;
        if (s.contains("phone")) return R.drawable.mdi_cellphone;
        if (s.contains("pair")) return R.drawable.mdi_cellphone;
        if (s.contains("wallpaper") || s.contains("photo")) return R.drawable.mdi_image;
        if (s.contains("input") || s.contains("source") || s.contains("television") || s.contains("tv model")) return R.drawable.mdi_television;
        if (s.contains("search")) return R.drawable.mdi_magnify;
        if (s.contains("clear")) return R.drawable.mdi_close;
        if (s.contains("save")) return R.drawable.mdi_content_save;
        if (s.contains("first-launch") || s.contains("first launch")) return R.drawable.mdi_rocket_launch;
        if (s.contains("what does") || s.contains("help") || s.contains("will not load")) return R.drawable.mdi_help_circle;
        if (s.contains("detect")) return R.drawable.mdi_map_marker;
        if (s.contains("type a city") || s.contains("type")) return R.drawable.mdi_magnify;
        if (s.contains("show this photo")) return R.drawable.mdi_image;
        if (s.contains("step 1")) return R.drawable.mdi_wifi;
        if (s.contains("step")) return R.drawable.mdi_cellphone;
        if (s.contains("current user")) return R.drawable.mdi_account;
        if (s.contains("turn off selected") || s.contains("run debloat")) return R.drawable.mdi_delete;
        return R.drawable.mdi_cog;
    }

    private void renderSelection() {
        for (int i = 0; i < itemViews.size(); i++) {
            boolean sel = i == selectedIdx;
            itemViews.get(i).setBackground(cardBg(sel));
            if (i < titleViews.size()) titleViews.get(i).setTextColor(sel ? Color.BLACK : Color.WHITE);
            // Tint every icon in the row (main + trailing load/delete), except
            // a real app icon, which must keep its own colours.
            {
                ViewGroup card = (ViewGroup) itemViews.get(i);
                boolean isApp = i < appIconFlags.size() && appIconFlags.get(i);
                for (int c = 0; c < card.getChildCount(); c++) {
                    View ch = card.getChildAt(c);
                    if (ch instanceof ImageView && !(isApp && ch == iconViews.get(i))) {
                        ((ImageView) ch).setColorFilter(sel ? Color.BLACK : Color.WHITE);
                    }
                }
            }
            if (i < descViews.size() && descViews.get(i) != null) descViews.get(i).setTextColor(sel ? 0x99000000 : 0x99FFFFFF);
            if (i < chevronViews.size() && chevronViews.get(i) != null) chevronViews.get(i).setTextColor(sel ? Color.BLACK : Color.WHITE);
        }
        if (!itemViews.isEmpty()) {
            View v = itemViews.get(selectedIdx);
            scrollView.post(() -> scrollView.smoothScrollTo(0, v.getTop()));
        }
    }

    @Override
    public void onBackPressed() {
        if (backAction != null) {
            Runnable back = backAction;
            backAction = null;
            back.run();
        } else {
            super.onBackPressed();
        }
    }

    private void show(String title, String sub, List<Item> items, Runnable back) {
        if (currentTitle != null && !currentTitle.isEmpty() && !currentItems.isEmpty()) {
            savedIndex.put(currentTitle, selectedIdx);
        }
        backAction = back;
        currentTitle = title;
        titleView.setText(title);
        subView.setText(sub);
        resultView.setText("");
        list.removeAllViews();
        itemViews.clear();
        titleViews.clear();
        iconViews.clear();
        appIconFlags.clear();
        descViews.clear();
        chevronViews.clear();
        currentItems.clear();
        currentItems.addAll(items);
        for (Item it : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 4));
            ImageView icon = new ImageView(this);
            android.graphics.drawable.Drawable rowIcon = it.appIcon;
            if (rowIcon == null && it.desc != null && it.desc.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+")) rowIcon = appIconFor(it.desc);
            if (rowIcon != null) icon.setImageDrawable(rowIcon);
            else if (it.iconRes != 0) icon.setImageResource(it.iconRes);
            else icon.setImageResource(glyphFor(it.label));
            iconViews.add(icon);
            appIconFlags.add(rowIcon != null);
            card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            TextView tv = Ui.text(this, 700, 18, Color.WHITE);
            tv.setSingleLine(true);
            tv.setText(it.label);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, -2, 1f);
            tvLp.leftMargin = Ui.dp(this, 20);
            tv.setLayoutParams(tvLp);
            card.addView(tv);
            titleViews.add(tv);
            // Apple TV Settings rows are one line: label left, short value
            // or a chevron right. Long description paragraphs are what made
            // v15 squished and hard to read, so they are not shown here.
            // The "What does each option do?" page still explains them.
            String value = menuValue(it.label);
            if (value == null) value = (it.desc != null && it.desc.length() <= 40) ? it.desc : "";
            TextView dv = Ui.text(this, 400, 16, 0x99FFFFFF);
            dv.setSingleLine(true);
            dv.setText(value);
            card.addView(dv);
            descViews.add(dv);
            if (it.noChevron) {
                chevronViews.add(null);
            } else if (it.trailIcon1 != 0 || it.trailIcon2 != 0) {
                // Backups rows: load icon and delete icon on the far right,
                // instead of a chevron. They are buttons in their own right.
                if (it.trailIcon1 != 0) {
                    ImageView tr1 = new ImageView(this);
                    tr1.setImageResource(it.trailIcon1);
                    tr1.setOnClickListener(v -> { if (it.trailRun1 != null) it.trailRun1.run(); });
                    card.addView(tr1, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
                }
                if (it.trailIcon2 != 0) {
                    ImageView tr2 = new ImageView(this);
                    tr2.setImageResource(it.trailIcon2);
                    tr2.setOnClickListener(v -> { if (it.trailRun2 != null) it.trailRun2.run(); });
                    LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
                    trLp.leftMargin = Ui.dp(this, 16);
                    tr2.setLayoutParams(trLp);
                    card.addView(tr2, trLp);
                }
                chevronViews.add(null);
            } else {
                TextView chev = Ui.text(this, 700, 36, Color.WHITE);
                chev.setSingleLine(true);
                chev.setText("\u203A");
                LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(-2, -2);
                chevLp.leftMargin = Ui.dp(this, 12);
                chev.setLayoutParams(chevLp);
                card.addView(chev);
                chevronViews.add(chev);
            }
            card.setFocusable(false);
            card.setFocusableInTouchMode(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            card.setLayoutParams(lp);
            card.setOnClickListener(v -> it.run.run());
            list.addView(card);
            itemViews.add(card);
        }
        int restore = savedIndex.getOrDefault(title, 0);
        selectedIdx = items.isEmpty() ? 0 : Math.min(restore, items.size() - 1);
        renderSelection();
    }

    /** Value shown in the main menu row, right of the label, tvOS style.
     *  Helper status is checked live: it starts as Checking, then the
     *  background LocalShell test rewrites the row to Connected or
     *  Disconnected, so the user never has to open it to find out. */
    private String updatesRowValue() {
        try {
            android.content.SharedPreferences up = getSharedPreferences("updates", MODE_PRIVATE);
            long latestCode = up.getLong("latest_code", 0);
            String latestName = up.getString("latest_name", "");
            long skipped = up.getLong("skipped_code", 0);
            if (latestCode > currentVersionCode() && latestCode != skipped && latestName != null && !latestName.isEmpty()) {
                return "Update available: " + latestName;
            }
        } catch (Exception ignored) {}
        return "Version " + currentVersionName();
    }

    private String menuValue(String label) {
        if (backAction != null) return null;
        if (label == null) return null;
        String s = label.toLowerCase(java.util.Locale.US);
        if (s.contains("updates") && label != null && label.equals("Updates")) return updatesRowValue();
        if (s.contains("helper status")) {
            final int idx = descViews.size();
            new Thread(() -> {
                boolean ok = shell.ready();
                runOnUiThread(() -> {
                    if (idx < descViews.size() && idx < currentItems.size()
                            && "Helper status".equals(currentItems.get(idx).label)) {
                        descViews.get(idx).setText(ok ? "Connected" : "Disconnected");
                    }
                });
            }).start();
            return "Checking...";
        }
        if (s.contains("backups")) {
            int n = backupFiles().size();
            return n == 0 ? "None saved" : n + " saved";
        }
        if (s.contains("users")) return setup.profiles().size() + " users";
        if (s.contains("weather")) {
            String place = setup.weatherPlace();
            return (place == null || place.isEmpty()) ? "Not set" : place;
        }
        if (s.contains("tv info")) return tvIp();
        if (s.contains("restore")) {
            // Count what is actually disabled on the TV right now, not the
            // wizard's own log: the log is wiped on a fresh install, so it
            // said "None disabled" while the Restore list showed a bunch.
            int n = setup.disabledApps().size();
            return n == 0 ? "None disabled" : n + " disabled";
        }
        if (s.contains("docks") || s.contains("hidden")) return "Per user";
        return null;
    }

    private void showMenu() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Backups", "Saved on the TV. Open to create a new backup, or load or delete an old one.", this::showBackups, R.drawable.mdi_file_restore));
        items.add(new Item("Helper status", "Shows Connected or Disconnected for the TV's local debugging connection. You need Connected to turn apps off or set home.", this::showHelper, R.drawable.mdi_signal_cellular_3));
        items.add(new Item("Disable apps / debloat", "Turn off junk apps and services. Disabled, not deleted, so you can bring them back in Restore.", this::showDebloatMenu, R.drawable.mdi_apps));
        items.add(new Item("Users", "No preset users: add your own here, or on the phone wizard page, then set each one's dock. Delete a user here too.", this::showUsers));
        items.add(new Item("First-launch setup again", "Run the guided first-launch setup again: a user, a dock, junk apps, and the home screen, step by step.", () -> startActivity(new Intent(this, FirstLaunchActivity.class))));
        items.add(new Item("Weather location", "Set the city for the home screen weather, or detect it from your IP. Nothing is baked in.", this::showWeather));
        items.add(new Item("Set this launcher as home", "Disable all other launchers, so this launcher opens when you press Home. Reversible in Restore.", this::showHome));
        items.add(new Item("Restore disabled apps", "Shows every disabled app on the TV. Bring back one at a time, or all at once.", this::showRestore));
        items.add(new Item("How to pair your phone", "Step by step: open the TV's phone page, pair it once, then run the wizard from your phone.", this::showPairHelp));
        items.add(new Item("TV info and IP", "Your TV's IP address, Wi-Fi, model, and the phone pages to open for the remote and the wizard.", this::showTvInfo));
        items.add(new Item("Wallpaper photos", "Photos already on this TV: show one now, or delete it. New photos come from a phone, they cannot be taken on the TV itself.", this::showPhotos));
        items.add(new Item("Unpair phones", "See every paired phone, unpair one, or unpair them all. A phone has to pair again before it can use the remote or wizard.", this::showUnpair));
        items.add(new Item("What does each option do?", "Plain explanations for every option in Settings, no jargon.", this::showHelp));
        items.add(new Item("Updates", "Check for a new version, see what changed, and update or skip it. Nothing installs on its own.", this::showUpdates, R.drawable.mdi_download));
        items.add(new Item("Configurations", "Export and import your setup.", this::showConfigurations, R.drawable.ic_settings));
        items.add(new Item("Done", "Back to home.", this::finish, R.drawable.mdi_exit_to_app));
        show("Launcher Settings", "One install only: no PC, no second helper app. Debloat and set-home use the TV's own local debugging connection. Add users in Users, or on the phone wizard page.", items, null);
        maybeAutoCheckUpdates();
    }

    /** Auto-check, at most once a day, when Settings opens. Silent unless
     *  an update is waiting: then a Toast points at Settings > Updates.
     *  It never installs anything, and never blocks opening Settings. */
    private void maybeAutoCheckUpdates() {
        try {
            android.content.SharedPreferences up = getSharedPreferences("updates", MODE_PRIVATE);
            long last = up.getLong("last_check", 0);
            if (System.currentTimeMillis() - last < 24L * 60 * 60 * 1000) return;
            checkUpdates(false);
        } catch (Exception ignored) {}
    }

    private String tvIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
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
        return "Unknown";
    }

    private String tvSsid() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null && wm.getConnectionInfo().getSSID() != null) {
                String s = wm.getConnectionInfo().getSSID().replace("\"", "");
                if (!s.isEmpty() && !s.equals("<unknown ssid>")) return s;
            }
        } catch (Exception ignored) {
        }
        return "Unknown (use the same Wi-Fi as your phone)";
    }


    private java.util.List<java.io.File> backupFiles() {
        java.io.File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        java.util.List<java.io.File> out = new java.util.ArrayList<>();
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.getName().startsWith("launcher-backup-") && f.getName().endsWith(".txt")) out.add(f);
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    private String backupDate(java.io.File f) {
        return new java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US).format(new java.util.Date(f.lastModified()));
    }

    /** Load a backup file: restores the profiles, docks and hidden lists
     *  recorded in it. Disabled apps are NOT re-disabled by loading;
     *  that state is handled by the Restore screen, so loading a backup
     *  cannot turn off apps you are currently using. */
    private void loadBackup(java.io.File f) {
        int restored = 0;
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("profile ")) continue;
                int nameAt = line.indexOf(" name=");
                int dockAt = line.indexOf(" dock=");
                int hiddenAt = line.indexOf(" hidden=");
                if (nameAt < 0 || dockAt < 0 || hiddenAt < 0) continue;
                String name = line.substring(nameAt + 6, dockAt).trim();
                String dockStr = line.substring(dockAt + 6, hiddenAt).trim();
                String hiddenStr = line.substring(hiddenAt + 8).trim();
                if (name.isEmpty()) continue;
                int idx = -1;
                java.util.List<SetupManager.ProfileInfo> ps = setup.profiles();
                for (int i = 0; i < ps.size(); i++) if (ps.get(i).name.equals(name)) { idx = i; break; }
                if (idx < 0) idx = setup.addProfile(name, false);
                if (idx < 0) continue;
                java.util.List<String> dock = new java.util.ArrayList<>();
                if (!dockStr.isEmpty()) for (String x : dockStr.split(",")) if (!x.trim().isEmpty()) dock.add(x.trim());
                java.util.Set<String> hidden = new java.util.HashSet<>();
                if (!hiddenStr.isEmpty()) for (String x : hiddenStr.split(",")) if (!x.trim().isEmpty()) hidden.add(x.trim());
                setup.saveDock(idx, dock);
                setup.saveHidden(idx, hidden);
                restored++;
            }
            br.close();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Load failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.Toast.makeText(this, "Loaded " + restored + " profiles from " + backupDate(f), android.widget.Toast.LENGTH_LONG).show();
        showBackups();
    }

    private void showBackups() {
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("Create a new backup", "Saves your profiles, docks, hidden lists and disabled apps to a file on the TV.", () -> {
            String path = setup.backup();
            android.widget.Toast.makeText(this, "Backup saved", android.widget.Toast.LENGTH_SHORT).show();
            showBackups();
        }, R.drawable.mdi_upload));
        for (java.io.File f : backupFiles()) {
            final java.io.File file = f;
            items.add(new Item("Backup", backupDate(file), () -> showBackupAction(file), R.drawable.mdi_file_restore));
        }
        show("Backups", "Tap a backup to load it or delete it. Loading restores profiles, docks and hidden lists from that backup.", items, this::showMenu);
    }

    private void showBackupAction(java.io.File file) {
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("Load this backup", "Restores the profiles, docks and hidden lists from " + backupDate(file) + ". Disabled apps are not re-disabled.", () -> loadBackup(file), R.drawable.mdi_backup_restore));
        items.add(new Item("Delete this backup", "Removes this backup file from the TV.", () -> {
            file.delete();
            android.widget.Toast.makeText(this, "Backup deleted", android.widget.Toast.LENGTH_SHORT).show();
            showBackups();
        }, R.drawable.mdi_delete_restore));
        show("Backup " + backupDate(file), "Load it, or delete it. Remote Back returns to the backups list.", items, this::showBackups);
    }

    // ------------------------------------------------------------ configurations (26.1.3)
    // The file list is read here, only when one of these screens opens:
    // never at home startup, never at the main menu, never in the
    // background. Each screen is one line per row, like the rest of
    // Settings, and Back uses the same saved-index map as every other
    // screen, so it returns to the parent with its position saved.
    // There is no share button on the TV: a TV has no share sheet, so
    // sharing is done from the phone page, which is stated in the
    // Export screen's footer line instead of a fake button.

    private String configDate(java.io.File f) {
        return new java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.US).format(new java.util.Date(f.lastModified()));
    }

    private String configSize(java.io.File f) {
        long kb = Math.max(1, f.length() / 1024);
        return kb + " KB";
    }

    private void showConfigurations() {
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("Export", "Create and manage exports.", this::showExport, R.drawable.mdi_upload));
        items.add(new Item("Import", "Apply a saved export.", this::showImport, R.drawable.mdi_download));
        show("Configurations", "Export saves your setup to a file. Import applies a saved file.", items, this::showMenu);
    }

    private boolean configInfoDismissed() {
        return getSharedPreferences("home", MODE_PRIVATE).getBoolean("config_info_dismissed", false);
    }

    private void dismissConfigInfo() {
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("config_info_dismissed", true).apply();
    }

    private void doCreateExport() {
        java.io.File f = SetupManager.exportConfig(this);
        Toast.makeText(this, f == null ? "Export failed" : "Export saved: " + f.getName(), Toast.LENGTH_SHORT).show();
        showExport();
    }

    private void showExportInfo() {
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("OK, create export", "Create the export now.", this::doCreateExport, R.drawable.mdi_upload));
        items.add(new Item("Don't show again", "Never show this again, create now.", () -> { dismissConfigInfo(); doCreateExport(); }, R.drawable.mdi_close));
        show("About exports", "What an export does and does not include.", items, this::showExport);
        resultView.setText("Exports save your profiles, docks, hidden apps, active profile, and weather location to a file on this TV. They do not include paired phones, the helper key, or wallpaper photos. Disabled apps are recorded in the file only, they are not turned off again on import. You can download, share, rename, or delete exports from the phone page.");
    }

    private void showImportInfo(java.io.File file) {
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("OK, continue", "Go to the import confirmation.", () -> showImportConfirm(file), R.drawable.mdi_download));
        items.add(new Item("Don't show again", "Never show this again, continue.", () -> { dismissConfigInfo(); showImportConfirm(file); }, R.drawable.mdi_close));
        show("About imports", "What an import replaces, and how to undo it.", items, this::showImport);
        resultView.setText("Importing replaces your profiles, docks, hidden apps, active profile, and weather with the ones in the file. Disabled apps in the file are recorded only, they will not be turned off again. Paired phones and wallpaper photos are not changed.");
    }

    private void showExport() {
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("Create new export", "Save your setup now.", () -> {
            if (configInfoDismissed()) doCreateExport(); else showExportInfo();
        }, R.drawable.mdi_upload));
        for (java.io.File f : SetupManager.configFiles(this)) {
            final java.io.File file = f;
            Item row = new Item(file.getName(), configDate(file) + "  ·  " + configSize(file),
                    () -> showConfigAction(file), R.drawable.mdi_content_save);
            row.withTrails(R.drawable.mdi_pencil, () -> startConfigRename(file),
                    R.drawable.mdi_delete, () -> {
                        if (SetupManager.deleteConfigFile(this, file.getName())) {
                            Toast.makeText(this, "Export deleted", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show();
                        }
                        showExport();
                    });
            items.add(row);
        }
        show("Export", "Tap a file to rename or delete it.", items, this::showConfigurations);
        resultView.setText("Exports live on this TV. Open the phone page to download and share them.");
    }

    private void startConfigRename(java.io.File file) {
        configRenameFile = file;
        Intent i = new Intent(this, TextEntryActivity.class);
        i.putExtra("title", "Rename export");
        i.putExtra("hint", "Type the new name, then Save. The .json ending is kept.");
        String name = file.getName();
        if (name.endsWith(".json")) name = name.substring(0, name.length() - 5);
        i.putExtra("initial", name);
        startActivityForResult(i, 105);
    }

    private void showConfigAction(java.io.File file) {
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("Rename this export", "Type a new name on the next screen.", () -> startConfigRename(file), R.drawable.mdi_pencil));
        items.add(new Item("Delete this export", "Removes this file from the TV.", () -> {
            if (SetupManager.deleteConfigFile(this, file.getName())) {
                Toast.makeText(this, "Export deleted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show();
            }
            showExport();
        }, R.drawable.mdi_delete));
        show(file.getName(), "Rename it, or delete it.", items, this::showExport);
    }

    private void showImport() {
        java.util.List<Item> items = new java.util.ArrayList<>();
        for (java.io.File f : SetupManager.configFiles(this)) {
            final java.io.File file = f;
            Item row = new Item(file.getName(), configDate(file) + "  ·  " + configSize(file),
                    () -> { if (configInfoDismissed()) showImportConfirm(file); else showImportInfo(file); }, R.drawable.mdi_download);
            row.withTrails(R.drawable.mdi_download, () -> { if (configInfoDismissed()) showImportConfirm(file); else showImportInfo(file); }, 0, null);
            items.add(row);
        }
        show("Import", "Tap a file to import it.", items, this::showConfigurations);
        if (items.isEmpty()) resultView.setText("No exports on this TV yet. Create one in Export, or upload one from the phone page.");
    }

    private void showImportConfirm(java.io.File file) {
        String summary;
        try {
            String text = SetupManager.readConfigFile(file);
            String err = SetupManager.validateConfigText(text);
            if (err != null) {
                Toast.makeText(this, "Cannot import: " + err, Toast.LENGTH_LONG).show();
                showImport();
                return;
            }
            summary = SetupManager.describeConfig(new org.json.JSONObject(text));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot read that file.", Toast.LENGTH_LONG).show();
            showImport();
            return;
        }
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("Import this configuration", "Replace your setup now.", () -> {
            String result = SetupManager.applyConfigFile(this, file);
            if (result != null && result.startsWith("ERR:")) {
                Toast.makeText(this, result.substring(4), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Imported.", Toast.LENGTH_LONG).show();
            }
            showImport();
        }, R.drawable.mdi_download));
        items.add(new Item("Keep current configuration", "Do not import this file.", this::showImport, R.drawable.mdi_close));
        show("Import " + file.getName() + "?", "Confirm below. Back cancels.", items, this::showImport);
        resultView.setText(summary);
    }

    private android.graphics.drawable.Drawable appIconFor(String pkg) {
        try { return getPackageManager().getApplicationIcon(pkg); }
        catch (Exception e) { return null; }
    }

    private void showPairHelp() {
        String ip = tvIp();
        List<Item> items = new ArrayList<>();
        // Tapping a step shows its full text under the title, so the link
        // in Step 2 is actually readable, instead of a row that does nothing.
        items.add(new Item("Step 1: Same Wi-Fi", "Your phone and the TV must be on the same Wi-Fi network. If the TV is on a different network, the phone page will not load.", () -> resultView.setText("Your phone and the TV must be on the same Wi-Fi network. If the TV is on a different network, the phone page will not load."), R.drawable.mdi_wifi_alert));
        items.add(new Item("Step 2: Open the phone page", "http://" + ip + ":8080", () -> resultView.setText("On your phone, open your browser and go to: http://" + ip + ":8080  That is the remote. For setup, go to: http://" + ip + ":8080/wizard"), R.drawable.mdi_cellphone_information));
        items.add(new Item("Step 3: Pair this phone", "On the phone page, type a phone name first if you want one, then tap Pair this phone. A prompt appears on the TV. No name means it shows as Unknown in Unpair phones.", () -> resultView.setText("On the phone page, type a phone name first if you want one, then tap Pair this phone. A prompt appears on the TV. No name means it shows as Unknown in Unpair phones."), R.drawable.mdi_cellphone_link));
        items.add(new Item("Step 4: Allow on the TV", "On the TV, with the remote, press Right to move to Allow, then OK. The phone is now paired and the TV remembers it.", () -> resultView.setText("On the TV, with the remote, press Right to move to Allow, then OK. The phone is now paired and the TV remembers it."), R.drawable.mdi_cellphone_check));
        items.add(new Item("Step 5: Use it", "The phone page stays paired until you tap Unpair this phone on the phone page. You can add it to your home screen from the browser share menu.", () -> resultView.setText("The phone page stays paired until you tap Unpair this phone on the phone page. You can add it to your home screen from the browser share menu."), R.drawable.mdi_monitor_cellphone_star));
        items.add(new Item("If it will not load", "Check the IP in TV info and IP, make sure you typed the :8080 port, and that the phone is not on mobile data or a VPN.", () -> resultView.setText("Check the IP in TV info and IP, make sure you typed the :8080 port, and that the phone is not on mobile data or a VPN."), R.drawable.mdi_alert_circle));
        show("How to pair your phone", "Pair once, then the phone can control the TV and run the wizard. Your TV's IP right now is " + ip + ". Tap a step to read it in full.", items, this::showMenu);
    }

    private void showTvInfo() {
        String ip = tvIp();
        String ssid = tvSsid();
        String wifiValue = ssid.startsWith("Unknown") ? "Connected" : ssid + " - Connected";
        List<Item> items = new ArrayList<>();
        items.add(new Item("IP address", ip, () -> {}).noArrow());
        items.add(new Item("Wi-Fi network", wifiValue, () -> {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception e) { android.widget.Toast.makeText(this, "Could not open Settings", android.widget.Toast.LENGTH_SHORT).show(); }
        }, R.drawable.mdi_wifi_cog));
        items.add(new Item("Phone remote page", "http://" + ip + ":8080", () -> {}).noArrow());
        items.add(new Item("Phone wizard page", "http://" + ip + ":8080/wizard", () -> {}).noArrow());
        items.add(new Item("TV model", android.os.Build.MODEL + "  -  Android " + android.os.Build.VERSION.RELEASE, () -> {}).noArrow());
        items.add(new Item("Current user", setup.profiles().size() > 0 ? setup.profiles().get(Math.min(Math.max(0, getSharedPreferences("home", MODE_PRIVATE).getInt("profile", 0)), setup.profiles().size() - 1)).name : "Unknown", () -> {}, R.drawable.mdi_account_circle).noArrow());
        items.add(new Item("Check helper status", "Go to Helper status to see Connected or Disconnected.", this::showHelper));
        show("TV info and IP", "Use the IP and the two phone pages below. If the IP changes, it is usually because the TV reconnected to Wi-Fi, just come back here and read it again.", items, this::showMenu);
    }

    private void showHelp() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Backups", "Saves a text file with your docks, hidden lists, profiles and every installed app with enabled or disabled next to it. It does not change anything, it is just a safety copy before you change apps.", () -> resultView.setText("Saves a text file with your docks, hidden lists, profiles and every installed app with enabled or disabled next to it. It does not change anything, it is just a safety copy before you change apps.")));
        items.add(new Item("Helper status", "Connected means the launcher can talk to the TV's own local debugging service and can turn apps off. Disconnected means network debugging is off, or you have not accepted Allow USB debugging with Always allow yet. Open Developer settings from that screen to turn it on.", () -> resultView.setText("Connected means the launcher can talk to the TV's own local debugging service and can turn apps off. Disconnected means network debugging is off, or you have not accepted Allow USB debugging with Always allow yet. Open Developer settings from that screen to turn it on.")));
        items.add(new Item("Disable apps / debloat", "Junk apps is a short safe list like YouTube Music, Samba TV telemetry and Sony promos. All apps and services lets you pick any enabled app or service, except protected core packages that would break the TV. Turning an app off is called disable-user: the app stays installed, it just cannot run or show up, and Restore can turn it back on.", () -> resultView.setText("Junk apps is a short safe list like YouTube Music, Samba TV telemetry and Sony promos. All apps and services lets you pick any enabled app or service, except protected core packages that would break the TV. Turning an app off is called disable-user: the app stays installed, it just cannot run or show up, and Restore can turn it back on.")));
        items.add(new Item("Users", "No users are built in. Add a user in Users, or on the phone wizard page, then set that user's dock and hidden apps. Delete a user in Users, Delete a user.", () -> resultView.setText("No users are built in. Add a user in Users, or on the phone wizard page, then set that user's dock and hidden apps. Delete a user in Users, Delete a user.")));
        items.add(new Item("Docks", "The row of apps at the bottom of home. Each user gets their own dock, up to 5 apps. Open Users, tap a user, then Docks.", () -> resultView.setText("The row of apps at the bottom of home. Each user gets their own dock, up to 5 apps. Open Users, tap a user, then Docks.")));
        items.add(new Item("Hidden apps", "Apps a user should not see on home. They are not deleted, they move to that user's Hidden folder tile at the end of the Apps grid. Open Users, tap a user, then Hidden apps.", () -> resultView.setText("Apps a user should not see on home. They are not deleted, they move to that user's Hidden folder tile at the end of the Apps grid. Open Users, tap a user, then Hidden apps.")));
        items.add(new Item("Weather location", "The city used for the home screen weather. Set a city on the phone wizard page, or detect it from your IP in Weather location. No city is baked in.", () -> resultView.setText("The city used for the home screen weather. Set a city on the phone wizard page, or detect it from your IP in Weather location. No city is baked in.")));
        items.add(new Item("Set this launcher as home", "Disable all other launchers, so pressing Home opens this launcher instead. If you ever want the old home back, use Restore and bring the stock home app back.", () -> resultView.setText("Disable all other launchers, so pressing Home opens this launcher instead. If you ever want the old home back, use Restore and bring the stock home app back.")));
        items.add(new Item("Restore disabled apps", "Lists every app on the TV that is currently disabled, including apps disabled before this wizard existed. Pick one to turn it back on, or Restore ALL to turn them all back on.", () -> resultView.setText("Lists every app on the TV that is currently disabled, including apps disabled before this wizard existed. Pick one to turn it back on, or Restore ALL to turn them all back on.")));
        items.add(new Item("How to pair your phone", "How to connect your phone to the TV's own webpage, so you can use the remote and the wizard from your phone without a PC.", () -> resultView.setText("How to connect your phone to the TV's own webpage, so you can use the remote and the wizard from your phone without a PC.")));
        items.add(new Item("TV info and IP", "The network details you need to open the phone pages: the TV's IP address, its Wi-Fi name, and the two URLs for the remote and the wizard.", () -> resultView.setText("The network details you need to open the phone pages: the TV's IP address, its Wi-Fi name, and the two URLs for the remote and the wizard.")));
        show("What does each option do?", "Tap an option below to read what it does. Back returns to Settings.", items, this::showMenu);
    }

    private void showHelper() {
        // Old version only showed the Helper screen AFTER the local ADB
        // test returned, so if that test hung (no prompt, network debugging
        // off, auth waiting) clicking Helper status looked like it did
        // nothing at all. Now the screen opens immediately with Checking,
        // Back and Check again always work, and a 15 second timeout shows
        // Disconnected with the manual steps instead of hanging forever.
        helperToken++;
        final int token = helperToken;
        helperDone = false;
        List<Item> checking = new ArrayList<>();
        checking.add(new Item("Open Developer settings", () -> openDeveloperSettings()));
        checking.add(new Item("Check again", this::showHelper));
        show("Helper status: Checking...", "Testing the TV's own local debugging connection. If this stalls, Developer options may not be enabled on this TV yet, or network debugging is off.", checking, this::showMenu);
        resultView.setText("Checking...");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (token != helperToken || helperDone) return;
            helperDone = true;
            List<Item> items = new ArrayList<>();
            items.add(new Item("Open Developer settings", () -> openDeveloperSettings()));
            items.add(new Item("Check again", this::showHelper));
            show("Helper status: Disconnected", "Timed out after 15 seconds. The TV did not answer its local debugging service. Turn on Developer options, then network debugging, then accept Allow USB debugging (Always allow). If Developer options is not in Settings yet, open Settings > Device Preferences > About > Build and tap Build 7 times, then Developer options appears.", items, this::showMenu);
            resultView.setText("Timed out. See the steps above.");
        }, 15_000);
        new Thread(() -> {
            boolean ready = shell.ready();
            String msg = (ready ? "Connected.\nLocal ADB is ready. The wizard can disable apps and set home."
                    : "Disconnected.\nLocal ADB is not ready: " + shell.lastError() + ". Turn on Developer options and network debugging on the TV, then accept Allow USB debugging (Always allow). If Developer options is not in Settings yet, open Settings > Device Preferences > About > Build and tap Build 7 times.");
            runOnUiThread(() -> {
                if (token != helperToken || helperDone) return;
                helperDone = true;
                List<Item> items = new ArrayList<>();
                items.add(new Item("Open Developer settings", () -> openDeveloperSettings()));
                items.add(new Item("Check again", this::showHelper));
                show("Helper status: " + (ready ? "Connected" : "Disconnected"), msg, items, this::showMenu);
                resultView.setText(ready ? "Connected." : "Disconnected. See the steps above.");
            });
        }).start();
    }

    private void openDeveloperSettings() {
        // ACTION_APPLICATION_DEVELOPMENT_SETTINGS does nothing on some
        // TVs when Developer options has not been unlocked yet, so fall
        // back to plain Settings with a Toast giving the manual path.
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
                Toast.makeText(this, "Developer options not unlocked yet: Settings > Device Preferences > About > Build, tap Build 7 times", Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Toast.makeText(this, "Could not open Settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showDebloatMenu() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Junk apps (recommended)", this::showDebloat, R.drawable.mdi_delete));
        items.add(new Item("All apps and services", this::showAllAppsDebloat, R.drawable.mdi_apps));
        show("Disable apps / debloat", "Junk apps is the short safe list. All apps and services lets you pick any enabled app or service, except protected core packages. Backup runs first, and Restore can bring an app back one by one.", items, this::showMenu);
    }

    private void showAllAppsDebloat() {
        List<SetupManager.Candidate> all = setup.enabledAllPackages();
        if (textQuery != null && !textQuery.isEmpty()) {
            String q = textQuery.toLowerCase();
            List<SetupManager.Candidate> filtered = new ArrayList<>();
            for (SetupManager.Candidate cand : all) {
                if (cand.label.toLowerCase().contains(q) || cand.pkg.toLowerCase().contains(q)) filtered.add(cand);
            }
            all = filtered;
        }
        Set<String> selected = new HashSet<>();
        List<Item> items = new ArrayList<>();
        items.add(new Item(textQuery.isEmpty() ? "Search apps..." : "Search: " + textQuery + " (tap to change)", "Type a few letters to filter this list, right here on the TV.", () -> {
            Intent i = new Intent(this, TextEntryActivity.class);
            i.putExtra("title", "Search apps");
            i.putExtra("hint", "Type a few letters, then Save. Empty text clears the search.");
            i.putExtra("initial", textQuery);
            startActivityForResult(i, 104);
        }));
        if (!textQuery.isEmpty()) {
            items.add(new Item("Clear search", "Show all apps and services again.", () -> {
                textQuery = "";
                showAllAppsDebloat();
            }));
        }
        for (SetupManager.Candidate cand : all) {
            items.add(new Item("[ ] " + cand.label, cand.pkg, () -> {
                if (selected.contains(cand.pkg)) selected.remove(cand.pkg); else selected.add(cand.pkg);
                if (selectedIdx < itemViews.size()) titleViews.get(selectedIdx).setText((selected.contains(cand.pkg) ? "[✓] " : "[ ] ") + cand.label);
                renderSelection();
            }));
        }
        items.add(new Item("Turn off selected (stop first, backup first)", () -> {
            List<String> pkgs = new ArrayList<>(selected);
            resultView.setText("Working...");
            final boolean[] done = {false};
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!done[0]) resultView.setText("Still working after 45 seconds. This usually means the TV is waiting for \"Allow USB debugging?\" (tick Always allow), or network debugging is off. Check Settings > Helper status, then try again.");
            }, 45_000);
            new Thread(() -> {
                String out;
                try {
                    String backupPath = setup.backup();
                    out = "Backup: " + backupPath + "\n" + setup.debloat(shell, pkgs, true);
                } catch (Exception e) {
                    out = "Debloat failed: " + String.valueOf(e.getMessage());
                }
                final String res = out;
                runOnUiThread(() -> { done[0] = true; resultView.setText(res); });
            }).start();
        }));
        show("All apps and services", "Use Up/Down to move, OK to pick an app or service. Protected core packages are not listed. Turning off a service can break something, so only pick apps you recognise, and use Restore to bring one back.", items, this::showDebloatMenu);
    }

    private void showRestore() {
        List<SetupManager.Candidate> disabled = setup.disabledApps();
        if (disabled.isEmpty()) {
            List<Item> items = new ArrayList<>();
            show("Restore disabled apps", "No disabled apps were found on this TV.", items, this::showMenu);
            return;
        }
        List<Item> items = new ArrayList<>();
        items.add(new Item("Restore ALL disabled apps", () -> {
            new Thread(() -> {
                String r = setup.restore(shell);
                runOnUiThread(() -> {
                    showRestore();
                    resultView.setText(r);
                });
            }).start();
        }));
        for (SetupManager.Candidate cand : disabled) {
            items.add(new Item("Restore " + cand.label, cand.pkg, () -> {
                new Thread(() -> {
                    String r = setup.restoreOne(shell, cand.pkg);
                    runOnUiThread(() -> {
                        showRestore();
                        resultView.setText(r);
                    });
                }).start();
            }));
        }
        show("Restore disabled apps", "Pick one app to bring back, or Restore ALL at the top. " + disabled.size() + " disabled apps found.", items, this::showMenu);
    }

    private void showDebloat() {
        List<SetupManager.Candidate> cands = setup.installedCandidates();
        Set<String> selected = new HashSet<>();
        for (SetupManager.Candidate cand : cands) if (cand.onByDefault) selected.add(cand.pkg);
        showDebloat(cands, selected);
    }

    private void showDebloat(List<SetupManager.Candidate> cands, Set<String> selected) {
        if (cands.isEmpty()) {
            List<Item> items = new ArrayList<>();
            show("Debloat", "No known junk apps are currently enabled on this TV.", items, this::showDebloatMenu);
            return;
        }
        // Toggles update the row in place, so the selection and scroll position
        // stay where they are instead of jumping back to the top on every tap.
        List<Item> items = new ArrayList<>();
        for (SetupManager.Candidate cand : cands) {
            items.add(new Item((selected.contains(cand.pkg) ? "[✓] " : "[ ] ") + cand.label, cand.pkg, () -> {
                if (selected.contains(cand.pkg)) selected.remove(cand.pkg); else selected.add(cand.pkg);
                // The row for the item just pressed is the selected row.
                if (selectedIdx < itemViews.size()) titleViews.get(selectedIdx).setText((selected.contains(cand.pkg) ? "[✓] " : "[ ] ") + cand.label);
                renderSelection();
            }));
        }
        items.add(new Item("Run debloat (selected apps, stop first, backup first)", () -> {
            List<String> pkgs = new ArrayList<>(selected);
            new Thread(() -> {
                String backupPath = setup.backup();
                String r = setup.debloat(shell, pkgs, true);
                runOnUiThread(() -> resultView.setText("Backup: " + backupPath + "\n" + r));
            }).start();
        }));
        show("Debloat", "Use Up/Down to move, OK to toggle an app, then Run debloat. Backup runs first, and Restore in Settings can bring apps back.", items, this::showDebloatMenu);
    }

    private void showUsers() {
        List<SetupManager.ProfileInfo> ps = setup.profiles();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            int idx = i;
            items.add(new Item(ps.get(i).name + (ps.get(i).kid ? " (kid)" : ""), "Dock: " + setup.dockFor(idx).size() + " apps, Hidden: " + setup.hiddenFor(idx).size() + " apps", () -> showUserDetail(idx), ps.get(i).kid ? R.drawable.mdi_account_child : R.drawable.mdi_account));
        }
        items.add(new Item("Add a user", "Type a name and pick Adult or Kid, right here on the TV, then pick their dock.", this::showAddUser, R.drawable.mdi_account_plus));
        items.add(new Item("Rename a user", "Type a new name for a user, right here on the TV.", this::showRenameUser, R.drawable.mdi_pencil));
        items.add(new Item("Toggle kid / adult", "Flip a user between kid and adult, right here on the TV.", this::showToggleKid, R.drawable.mdi_account_switch));
        items.add(new Item("Delete a user", "Removes a user and their dock and hidden list. Other users are untouched, because each user is one JSON object.", this::showDeleteUser, R.drawable.mdi_delete));
        show("Users", ps.isEmpty() ? "No users yet. Add one here, with a name you type on the TV." : "Tap a user to edit their dock. Rename and kid settings are in the list below.", items, this::showMenu);
    }

    private void showUserDetail(int idx) {
        java.util.List<SetupManager.ProfileInfo> ps = setup.profiles();
        if (idx < 0 || idx >= ps.size()) { showUsers(); return; }
        // Fix an existing dock+hidden overlap before counting: an app that
        // is hidden cannot stay in the dock, otherwise home shows 4 apps
        // while this screen still says 5.
        {
            java.util.List<String> dock = new java.util.ArrayList<>(setup.dockFor(idx));
            java.util.Set<String> hidden = setup.hiddenFor(idx);
            if (dock.removeAll(hidden)) setup.saveDock(idx, dock);
        }
        String name = ps.get(idx).name;
        java.util.List<Item> items = new java.util.ArrayList<>();
        items.add(new Item("Docks", setup.dockFor(idx).size() + " apps, max 5", () -> showDockPick(idx), R.drawable.mdi_dock_bottom));
        items.add(new Item("Hidden apps", setup.hiddenFor(idx).size() + " apps hidden for this user only", () -> showHiddenPick(idx), R.drawable.mdi_eye_off));
        show(name, "Pick Docks or Hidden apps for " + name + ". The lists work the same way: Up/Down to move, OK to add or remove an app. Changes save as you tap. Hiding an app also takes it off this user's dock.", items, this::showUsers);
    }

    private void showAddUser() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Adult", "Type a name for an adult user.", () -> {
            textKid = false;
            Intent i = new Intent(this, TextEntryActivity.class);
            i.putExtra("title", "New user name");
            i.putExtra("hint", "Type a name, then Save.");
            i.putExtra("initial", "User " + (setup.profiles().size() + 1));
            i.putExtra("capWords", true);
            startActivityForResult(i, 101);
        }));
        items.add(new Item("Kid", "Type a name for a kid user.", () -> {
            textKid = true;
            Intent i = new Intent(this, TextEntryActivity.class);
            i.putExtra("title", "New user name");
            i.putExtra("hint", "Type a name, then Save.");
            i.putExtra("initial", "User " + (setup.profiles().size() + 1));
            i.putExtra("capWords", true);
            startActivityForResult(i, 101);
        }));
        show("Add a user", "Pick Adult or Kid, then type the name on the next screen.", items, this::showUsers);
    }

    private void showRenameUser() {
        List<SetupManager.ProfileInfo> ps = setup.profiles();
        List<Item> items = new ArrayList<>();
        if (ps.isEmpty()) {
            show("Rename a user", "No users to rename.", items, this::showUsers);
            return;
        }
        for (int i = 0; i < ps.size(); i++) {
            int idx = i;
            items.add(new Item("Rename " + ps.get(i).name, "Type a new name on the next screen.", () -> {
                textIdx = idx;
                Intent in = new Intent(this, TextEntryActivity.class);
                in.putExtra("title", "Rename " + ps.get(idx).name);
                in.putExtra("hint", "Type the new name, then Save.");
                in.putExtra("initial", ps.get(idx).name);
                in.putExtra("capWords", true);
                startActivityForResult(in, 102);
            }));
        }
        show("Rename a user", "Pick a user, then type the new name.", items, this::showUsers);
    }

    private void showToggleKid() {
        List<SetupManager.ProfileInfo> ps = setup.profiles();
        List<Item> items = new ArrayList<>();
        if (ps.isEmpty()) {
            show("Toggle kid / adult", "No users to change.", items, this::showUsers);
            return;
        }
        for (int i = 0; i < ps.size(); i++) {
            int idx = i;
            items.add(new Item(ps.get(i).name + (ps.get(i).kid ? " (kid)" : " (adult)"), ps.get(i).kid ? "Make this an adult user." : "Make this a kid user.", () -> {
                setup.setKid(idx, !ps.get(idx).kid);
                showToggleKid();
            }));
        }
        show("Toggle kid / adult", "Pick a user to flip their kid flag.", items, this::showUsers);
    }

    private void showPhotos() {
        List<java.io.File> photos = Wallpapers.userPhotos(this);
        List<Item> items = new ArrayList<>();
        if (photos.isEmpty()) {
            show("Wallpaper photos", "No photos on this TV yet. Photos are added from a phone.", items, this::showMenu);
            return;
        }
        for (java.io.File f : photos) {
            String id = f.getName().replace(".jpg", "");
            items.add(new Item("Photo " + id + " (" + (f.length() / 1024) + " KB)", "Show it now, or delete it, on the next screen.", () -> {
                List<Item> sub = new ArrayList<>();
                sub.add(new Item("Show this photo now", "Puts this photo on screen as the wallpaper.", () -> {
                    Wallpapers.show(this, id);
                    Toast.makeText(this, "Showing photo", Toast.LENGTH_SHORT).show();
                    showPhotos();
                }));
                sub.add(new Item("Delete this photo", "Removes it from the TV. This cannot be undone here.", () -> {
                    Wallpapers.delete(this, id);
                    Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show();
                    showPhotos();
                }));
                show("Photo " + id, "Show it, or delete it.", sub, this::showPhotos);
            }));
        }
        show("Wallpaper photos", photos.size() + " photos on this TV. Pick one to show or delete it.", items, this::showMenu);
    }

    private void showUnpair() {
        android.content.SharedPreferences phone = getSharedPreferences("phone", MODE_PRIVATE);
        java.util.Set<String> tokens = new java.util.HashSet<>(phone.getStringSet("tokens", new java.util.HashSet<>()));
        java.util.Map<String, String> names = new java.util.HashMap<>();
        try {
            org.json.JSONObject no = new org.json.JSONObject(phone.getString("token_names", "{}"));
            java.util.Iterator<String> keys = no.keys();
            while (keys.hasNext()) { String k = keys.next(); names.put(k, no.optString(k, "")); }
        } catch (Exception ignored) {}
        java.util.Map<String, Long> times = new java.util.HashMap<>();
        for (String kv : phone.getString("token_times", "").split(",")) {
            int e = kv.indexOf(':');
            if (e <= 0) continue;
            try { times.put(kv.substring(0, e), Long.parseLong(kv.substring(e + 1))); } catch (Exception ignored) {}
        }
        // The TV never saved a phone name, only when it paired, so phones are
        // listed by paired date, oldest first. Legacy tokens with no date go last.
        java.util.List<String> sorted = new java.util.ArrayList<>(tokens);
        java.util.Collections.sort(sorted, (a, b) -> {
            Long ta = times.get(a); Long tb = times.get(b);
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1; if (tb == null) return -1;
            return ta.compareTo(tb);
        });
        java.util.List<Item> items = new java.util.ArrayList<>();
        if (!sorted.isEmpty()) {
            items.add(new Item("Unpair all phones", "Every phone, at once.", () -> {
                phone.edit().remove("tokens").remove("token_times").remove("token_names").apply();
                Toast.makeText(this, "All phones unpaired", Toast.LENGTH_SHORT).show();
                showMenu();
            }));
            for (int i = 0; i < sorted.size(); i++) {
                final String token = sorted.get(i);
                final int n = i + 1;
                Long at = times.get(token);
                String when = at == null ? "date not saved" : new java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(new java.util.Date(at));
                String rawName = names.get(token);
                final String phoneName = (rawName == null || rawName.isEmpty()) ? "Unknown" : rawName;
                items.add(new Item(phoneName, when, () -> {
                    java.util.Set<String> rest = new java.util.HashSet<>(phone.getStringSet("tokens", new java.util.HashSet<>()));
                    rest.remove(token);
                    java.util.Map<String, Long> restTimes = new java.util.HashMap<>();
                    for (String kv : phone.getString("token_times", "").split(",")) {
                        int e = kv.indexOf(':');
                        if (e <= 0) continue;
                        try { restTimes.put(kv.substring(0, e), Long.parseLong(kv.substring(e + 1))); } catch (Exception ignored) {}
                    }
                    restTimes.remove(token);
                    names.remove(token);
                    try { phone.edit().putString("token_names", new org.json.JSONObject(names).toString()).apply(); } catch (Exception ignored) {}
                    StringBuilder sb = new StringBuilder();
                    for (java.util.Map.Entry<String, Long> e : restTimes.entrySet()) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(e.getKey()).append(':').append(e.getValue());
                    }
                    phone.edit().putStringSet("tokens", rest).putString("token_times", sb.toString()).apply();
                    Toast.makeText(this, phoneName + " unpaired", Toast.LENGTH_SHORT).show();
                    showUnpair();
                }));
            }
        }
        show("Unpair phones", sorted.isEmpty() ? "No phones are paired right now." : "The TV saved when each phone paired, not a phone name, so phones are listed by paired date. Back leaves them paired.", items, this::showMenu);
    }

    private void showDeleteUser() {
        List<SetupManager.ProfileInfo> ps = setup.profiles();
        List<Item> items = new ArrayList<>();
        if (ps.isEmpty()) {
            show("Delete a user", "No users to delete.", items, this::showUsers);
            return;
        }
        for (int i = 0; i < ps.size(); i++) {
            int idx = i;
            String name = ps.get(i).name;
            items.add(new Item("Delete " + name, "Tap again to confirm on the next screen.", () -> {
                List<Item> confirm = new ArrayList<>();
                confirm.add(new Item("Yes, delete " + name, "Their dock and hidden list go with them. Other users are untouched.", () -> {
                    setup.deleteProfile(idx);
                    Toast.makeText(this, name + " deleted", Toast.LENGTH_SHORT).show();
                    showUsers();
                }));
                confirm.add(new Item("Keep " + name, "Do not delete this user.", this::showDeleteUser, R.drawable.mdi_close));
                show("Delete " + name + "?", "This cannot be undone from here. Add the user again if you change your mind.", confirm, this::showDeleteUser);
            }));
        }
        show("Delete a user", "Pick a user to delete. You will be asked to confirm.", items, this::showUsers);
    }

    private void showWeather() {
        String place = setup.weatherPlace();
        List<Item> items = new ArrayList<>();
        items.add(new Item(place.isEmpty() ? "Weather: not set" : "Weather: " + place, "Home screen weather uses this place. Type a city here, or detect from IP below.", () -> {}).noArrow());
        items.add(new Item("Type a city or coordinates", "Type a city, or latitude,longitude, right here on the TV. It is geocoded, no key needed.", () -> {
            Intent i = new Intent(this, TextEntryActivity.class);
            i.putExtra("title", "Weather location");
            i.putExtra("hint", "Type a city, or latitude,longitude, then Save.");
            i.putExtra("initial", place);
            startActivityForResult(i, 103);
        }));
        items.add(new Item("Detect from IP location", "Uses your public IP (city level) to set the weather place. A typed city on the phone wizard overrides this.", () -> {
            new Thread(() -> {
                String p = setup.detectWeatherFromIp();
                runOnUiThread(() -> {
                    // show() clears resultView, so set the message after it, not before.
                    showWeather();
                    resultView.setText(p == null ? "IP detection failed, try again." : "Weather set to " + p);
                });
            }).start();
        }));
        items.add(new Item("Clear saved location", "Removes the saved place. Home will detect from IP again next time it refreshes.", () -> {
            setup.clearWeatherLocation();
            resultView.setText("Weather location cleared.");
            showWeather();
        }));
        show("Weather location", "No city is baked in. Type a city here, or detect from IP.", items, this::showMenu);
    }

    private void showProfilePick(String kind) {
        List<SetupManager.ProfileInfo> ps = setup.profiles();
        List<Item> items = new ArrayList<>();
        if (ps.isEmpty()) {
            show("Pick a user", "No users yet. Add one in Users first.", items, this::showUsers);
            return;
        }
        for (int i = 0; i < ps.size(); i++) {
            int idx = i;
            items.add(new Item(ps.get(i).name + (ps.get(i).kid ? " (kid)" : ""), ps.get(i).kid ? "Kid profile" : "Dock: " + setup.dockFor(i).size() + " apps", () -> {
                if ("dock".equals(kind)) showDockPick(idx); else showHiddenPick(idx);
            }));
        }
        show("Pick a user", "Which user do you want to edit?", items, this::showUsers);
    }

    private void showDockPick(int idx) {
        java.util.List<SetupManager.ProfileInfo> ps = setup.profiles();
        String name = ps.get(idx).name;
        java.util.Set<String> hidden = setup.hiddenFor(idx);
        java.util.List<String> dock = new java.util.ArrayList<>(setup.dockFor(idx));
        // Hidden apps are simply not shown here. They are NOT removed from
        // the saved dock: dropping them silently would delete a dock app
        // the user never asked to remove.
        java.util.List<Item> items = new java.util.ArrayList<>();
        for (AppRepo.App app : setup.allAppsLabelsOnly()) {
            if (hidden.contains(app.pkg)) continue;
            boolean inDock = dock.contains(app.pkg);
            items.add(new Item((inDock ? "[✓] " : "[ ] ") + app.label, app.pkg, () -> {
                if (dock.contains(app.pkg)) dock.remove(app.pkg);
                else if (dock.size() < 5) dock.add(app.pkg);
                else Toast.makeText(this, "Dock is full at 5 apps", Toast.LENGTH_SHORT).show();
                setup.saveDock(idx, dock);
                if (selectedIdx < itemViews.size()) titleViews.get(selectedIdx).setText((dock.contains(app.pkg) ? "[✓] " : "[ ] ") + app.label);
                renderSelection();
            }));
        }
        show(name + " dock", "Use Up/Down to move, OK to add or remove an app, max 5. Changes save as you tap. Apps hidden for this user are not shown here.", items, () -> showUserDetail(idx));
    }

    private void showHiddenPick(int idx) {
        java.util.List<SetupManager.ProfileInfo> ps = setup.profiles();
        String name = ps.get(idx).name;
        java.util.Set<String> hidden = new java.util.HashSet<>(setup.hiddenFor(idx));
        java.util.List<Item> items = new java.util.ArrayList<>();
        for (AppRepo.App app : setup.allAppsLabelsOnly()) {
            boolean isHidden = hidden.contains(app.pkg);
            items.add(new Item((isHidden ? "[✓] " : "[ ] ") + app.label, app.pkg, () -> {
                if (hidden.contains(app.pkg)) hidden.remove(app.pkg);
                else {
                    hidden.add(app.pkg);
                    // Hide means off the dock too: remove it from the dock
                    // first, so the dock count drops from 5 to 4 right away
                    // instead of home showing 4 while Settings still says 5.
                    java.util.List<String> dock = new java.util.ArrayList<>(setup.dockFor(idx));
                    if (dock.remove(app.pkg)) setup.saveDock(idx, dock);
                }
                setup.saveHidden(idx, hidden);
                if (selectedIdx < itemViews.size()) titleViews.get(selectedIdx).setText((hidden.contains(app.pkg) ? "[✓] " : "[ ] ") + app.label);
                renderSelection();
            }));
        }
        show(name + " hidden apps", "Use Up/Down to move, OK to hide or unhide an app for this user only. Changes save as you tap. Hiding an app also takes it off this user's dock.", items, () -> showUserDetail(idx));
    }

    private void showHome() {
        List<java.util.Map<String, String>> homes = setup.homePackages();
        List<Item> items = new ArrayList<>();
        if (homes.isEmpty()) {
            show("Set as home", "Disable all other launchers. No other home app was found. This launcher may already be the only home, or the stock home is already disabled. A stock home that is already disabled, like Google TV, shows in Restore disabled apps, not here.", items, this::showMenu);
            return;
        }
        for (java.util.Map<String, String> h : homes) {
            String pkg = h.get("pkg");
            items.add(new Item("Disable " + h.get("label") + " (" + pkg + ")", () -> {
                new Thread(() -> {
                    String r = setup.setHome(shell, pkg);
                    runOnUiThread(() -> resultView.setText(r));
                }).start();
            }));
        }
        show("Set as home", "Disable all other launchers. This is reversible: Restore disabled apps in Settings brings them back.", items, this::showMenu);
    }
    // ---------------------------------------------------------------- updates
    // Version scheme: 26.1, 26.1.2, 26.2 ... 26 is the year, the second
    // number is a major change, the third (when present) is a small fix.
    // versionCode packs them as year*10000 + major*100 + fix, so 26.1 is
    // 260100 and 26.1.2 is 260102, and comparing codes is comparing versions.
    private static final String UPDATE_URL = "https://raw.githubusercontent.com/stevi2k/android-tv-launcher/main/update.json";

    private long currentVersionCode() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
            return info.versionCode;
        } catch (Exception e) { return 0; }
    }

    private String currentVersionName() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception e) { return "unknown"; }
    }

    private static int[] parseVersion(String v) {
        int[] out = new int[]{0, 0, 0};
        if (v == null) return out;
        String[] parts = v.trim().split("\\.");
        for (int i = 0; i < parts.length && i < 3; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (Exception ignored) {}
        }
        return out;
    }

    private static int compareVersion(String a, String b) {
        int[] x = parseVersion(a), y = parseVersion(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
        }
        return 0;
    }

    private boolean updateAvailable(long remoteCode, String remoteName) {
        if (remoteCode > 0) return remoteCode > currentVersionCode();
        return compareVersion(remoteName, currentVersionName()) > 0;
    }

    private void showUpdates() {
        android.content.SharedPreferences up = getSharedPreferences("updates", MODE_PRIVATE);
        long latestCode = up.getLong("latest_code", 0);
        String latestName = up.getString("latest_name", "");
        String changelog = up.getString("latest_changelog", "");
        String apkUrl = up.getString("latest_apk_url", "");
        long skipped = up.getLong("skipped_code", 0);
        boolean available = latestCode > 0 && updateAvailable(latestCode, latestName);
        boolean skippedThis = available && latestCode == skipped;
        List<Item> items = new ArrayList<>();
        items.add(new Item("Current version", currentVersionName(), () -> {}, R.drawable.mdi_information).noArrow());
        items.add(new Item("Check for updates", "Compare this version with the latest on GitHub.", () -> checkUpdates(true), R.drawable.mdi_refresh));
        if (latestCode == 0) {
            items.add(new Item("No update check yet", "Tap Check for updates to see if a newer version is out.", () -> {}, R.drawable.mdi_information).noArrow());
        } else if (available) {
            final String url = apkUrl;
            items.add(new Item("Update to " + latestName, "Download and install it. You confirm in the system installer.", () -> startUpdate(url), R.drawable.mdi_download));
            if (skippedThis) {
                items.add(new Item("You skipped this version", "Tap Update above to install it anyway.", () -> {}, R.drawable.mdi_information).noArrow());
            } else {
                final long code = latestCode;
                items.add(new Item("Skip this version", "Don't offer " + latestName + " again. A manual check will still show it.", () -> {
                    up.edit().putLong("skipped_code", code).apply();
                    Toast.makeText(this, "Skipped " + latestName, Toast.LENGTH_SHORT).show();
                    showUpdates();
                }, R.drawable.mdi_close));
            }
        } else {
            items.add(new Item("You're on the latest version", latestName.isEmpty() ? currentVersionName() : latestName, () -> {}, R.drawable.mdi_check).noArrow());
        }
        show("Updates", "Nothing installs on its own. Check, read what changed, then update or skip.", items, this::showMenu);
        if (available && changelog != null && !changelog.isEmpty()) {
            String text = changelog.length() > 1000 ? changelog.substring(0, 1000) : changelog;
            resultView.setText("What's new in " + latestName + ":\n" + text);
        } else if (available) {
            resultView.setText("A newer version is available: " + latestName + ". No change notes were published for it.");
        }
    }

    /** Fetches update.json from the public repo. manual=true means the user
     *  tapped Check, so failures are shown; the daily auto-check stays silent. */
    private void checkUpdates(boolean manual) {
        if (manual) resultView.setText("Checking...");
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(UPDATE_URL + "?t=" + System.currentTimeMillis());
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("User-Agent", "android-tv-launcher");
                conn.setUseCaches(false);
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
                in.close();
                org.json.JSONObject o = new org.json.JSONObject(buf.toString("UTF-8"));
                final long code = o.optLong("versionCode", 0);
                final String name = o.optString("versionName", "");
                final String changelog = o.optString("changelog", "");
                final String apkUrl = o.optString("apkUrl", "");
                runOnUiThread(() -> {
                    getSharedPreferences("updates", MODE_PRIVATE).edit()
                        .putLong("last_check", System.currentTimeMillis())
                        .putLong("latest_code", code)
                        .putString("latest_name", name)
                        .putString("latest_changelog", changelog)
                        .putString("latest_apk_url", apkUrl)
                        .apply();
                    if ("Updates".equals(currentTitle)) {
                        showUpdates();
                        if (code > 0 && updateAvailable(code, name)) {
                            resultView.setText(changelog.isEmpty() ? ("A newer version is available: " + name + ".") : ("What's new in " + name + ":\n" + (changelog.length() > 1000 ? changelog.substring(0, 1000) : changelog)));
                        } else if (code > 0) {
                            resultView.setText("You're on the latest version.");
                        }
                    } else if (code > 0 && updateAvailable(code, name)) {
                        android.content.SharedPreferences up = getSharedPreferences("updates", MODE_PRIVATE);
                        if (code != up.getLong("skipped_code", 0)) {
                            Toast.makeText(this, "Update available: " + name + " - see Settings > Updates", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> {
                    if ("Updates".equals(currentTitle)) resultView.setText("Couldn't check for updates: " + e.getMessage() + ". Check the TV is online and try again.");
                });
            }
        }).start();
    }

    /** Downloads the APK and hands it to the system installer. The TV asks
     *  to allow this app to install unknown apps the first time; allow it,
     *  then tap Update again. Profiles, docks and hidden lists survive,
     *  because install -r keeps the app's data. */
    private void startUpdate(String apkUrl) {
        if (apkUrl == null || apkUrl.isEmpty()) {
            resultView.setText("This update has no download link yet.");
            return;
        }
        resultView.setText("Downloading...");
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(getExternalFilesDir(null), "updates");
                if (!dir.exists()) dir.mkdirs();
                java.io.File out = new java.io.File(dir, "launcher-update.apk");
                java.net.URL url = new java.net.URL(apkUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(60_000);
                conn.setRequestProperty("User-Agent", "android-tv-launcher");
                java.io.InputStream in = conn.getInputStream();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) >= 0) fos.write(chunk, 0, n);
                fos.close();
                in.close();
                runOnUiThread(() -> {
                    try {
                        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, "com.stevi2k.atvhome.fileprovider", out);
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, "application/vnd.android.package-archive");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        resultView.setText("Installer opened. Confirm Install, then the launcher restarts on the new version.");
                    } catch (Exception e) {
                        resultView.setText("Couldn't open the installer: " + e.getMessage() + ". Allow 'Install unknown apps' for this launcher in TV Settings, then try again.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> resultView.setText("Download failed: " + e.getMessage()));
            }
        }).start();
    }

}
