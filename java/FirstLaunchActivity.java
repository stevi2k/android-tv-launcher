package com.kulwant.atvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private final List<View> itemViews = new ArrayList<>();
    private final List<TextView> titleViews = new ArrayList<>();
    private final List<TextView> descViews = new ArrayList<>();
    private final List<Item> currentItems = new ArrayList<>();
    private int selectedIdx;
    private int step; // 0 welcome, 1 who, 2 dock, 3 junk, 4 home, 5 done
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

        root.addView(titleView);
        root.addView(subView);
        root.addView(resultView);
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
        if (step == 0) {
            finishDone();
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
            case 3:
                if (userIdx < 0) { step = 4; showStep(); } else showDock();
                break;
            case 4: showJunk(); break;
            case 5: showHome(); break;
            default: showDone(); break;
        }
    }

    private void showWelcome() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Start setup", "5 easy steps: a user, a name, a home row, junk apps, and the home screen.", () -> { step = 1; showStep(); }));
        items.add(new Item("Skip setup for now", "Go straight to the home screen. Everything here is still in Settings later.", this::finishDone));
        show("Welcome to Home", "Step 1 of 6  •  Set up in a minute, on the TV.", items);
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
        items.add(new Item("Skip this step", "No user yet. You can add one later in Settings.", () -> { step = 4; showStep(); }));
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
            items.add(new Item((dockPicked.contains(a.pkg) ? "[x] " : "[ ] ") + a.label, a.pkg, () -> {
                if (dockPicked.contains(a.pkg)) {
                    dockPicked.remove(a.pkg);
                } else if (dockPicked.size() < 5) {
                    dockPicked.add(a.pkg);
                } else {
                    Toast.makeText(this, "Dock holds 5 apps", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (selectedIdx < titleViews.size()) {
                    titleViews.get(selectedIdx).setText((dockPicked.contains(a.pkg) ? "[x] " : "[ ] ") + a.label);
                }
                setup.saveDock(userIdx, new ArrayList<>(dockPicked));
                renderSelection();
            }));
        }
        show("Pick your home row", "Step 4 of 6  •  " + userName + "  •  OK to tick an app, up to 5. Picks save as you tap, then Continue.", items);
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
                step = 5; showStep();
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
                    if (!junkAbandoned) { step = 5; showStep(); }
                });
            }).start();
        }));
        items.add(new Item("No, keep everything", "Skip this. You can remove junk apps later in Settings.", () -> { step = 5; showStep(); }));
        show("Remove junk apps?", "Step 5 of 6  •  A backup runs first, so this is reversible.", items);
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
                runOnUiThread(() -> { homeRunning = false; if (!homeAbandoned) { step = 6; showStep(); } });
            }).start();
        }));
        items.add(new Item("No, keep the stock home", "Open Home from the Apps tab whenever you want it. You can switch later in Settings.", () -> { step = 6; showStep(); }));
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
        if (userIdx >= 0) sub.append(userName).append(" was created");
        else sub.append("No user was created");
        sub.append(", with ").append(dockPicked.size()).append(" dock app(s). ");
        sub.append("Rename a user on the phone wizard page, or add more users in Settings.");
        show("You're set up", sub.toString(), items);
    }
}
