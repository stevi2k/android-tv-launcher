package com.kulwant.atvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads launchable apps, their banners and a glow colour sampled from each banner. */
final class AppRepo {

    static final class App {
        String pkg;
        String label;
        ComponentName component;
        boolean leanback;
        Bitmap banner;
        int glow;
    }

    static final class Result {
        final List<App> dock = new ArrayList<>();
        final List<App> grid = new ArrayList<>();
    }

    private static final int BANNER_W = 320, BANNER_H = 180;

    /**
     * One JSON object per user, stored in prefs "user_profiles_json":
     * {"id": "...", "name": "...", "kid": false, "dock": [...], "hidden": [...]}.
     * This is why deleting a user is safe: a user's dock and hidden list live
     * inside their own object, not in separate keys keyed by position, so
     * removing one object cannot shift another user's dock or hidden list.
     * No users are preset: a build whose config list is empty starts with [].
     * First run only, old profiles are migrated once: config profiles, with
     * any wizard renames, plus the old per-position dock_order_<idx> and
     * hidden_pkgs_<idx> values folded into the matching object. An empty
     * config is not saved, so a later build with profiles in config can
     * still migrate them.
     */
    static final class Profile {
        String id;
        String name;
        final List<String> dock = new ArrayList<>();
        final Set<String> hidden = new HashSet<>();
        boolean kid;
    }

    static List<Profile> loadProfiles(Context c) {
        SharedPreferences prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        String json = prefs.getString("user_profiles_json", null);
        if (json != null) {
            List<Profile> out = new ArrayList<>();
            try {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    Profile p = new Profile();
                    p.id = o.optString("id", "");
                    if (p.id.isEmpty()) p.id = java.util.UUID.randomUUID().toString();
                    p.name = o.optString("name", "").trim();
                    if (p.name.isEmpty()) continue;
                    p.kid = o.optBoolean("kid", false);
                    org.json.JSONArray d = o.optJSONArray("dock");
                    if (d != null) for (int j = 0; j < d.length(); j++) p.dock.add(d.optString(j));
                    org.json.JSONArray h = o.optJSONArray("hidden");
                    if (h != null) for (int j = 0; j < h.length(); j++) p.hidden.add(h.optString(j));
                    out.add(p);
                }
            } catch (Exception ignored) {
                // corrupt JSON: fall through to migration, which rebuilds from config
                return migrateProfiles(c, prefs);
            }
            return out;
        }
        return migrateProfiles(c, prefs);
    }

    private static List<Profile> migrateProfiles(Context c, SharedPreferences prefs) {
        List<Profile> out = new ArrayList<>();
        String[] config = c.getResources().getStringArray(R.array.profiles);
        List<String> names = new ArrayList<>();
        for (String s : prefs.getString("profile_names", "").split(",")) {
            s = s.trim();
            if (!s.isEmpty()) names.add(s);
        }
        for (int i = 0; i < config.length; i++) {
            String[] parts = config[i].split("\\|", -1);
            if (parts.length < 1 || parts[0].trim().isEmpty()) continue;
            Profile p = new Profile();
            p.id = java.util.UUID.randomUUID().toString();
            p.name = (i < names.size()) ? names.get(i) : parts[0].trim();
            // Old per-position dock wins over the config dock, when one was saved.
            String savedDock = prefs.getString("dock_order_" + i, null);
            if (savedDock != null) {
                for (String pkg : savedDock.split(",")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty()) p.dock.add(pkg);
                }
            } else if (parts.length > 1) {
                for (String pkg : parts[1].split(",")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty()) p.dock.add(pkg);
                }
            }
            for (String pkg : prefs.getString("hidden_pkgs_" + i, "").split(",")) {
                pkg = pkg.trim();
                if (!pkg.isEmpty()) p.hidden.add(pkg);
            }
            p.kid = parts.length > 2 && parts[2].trim().equalsIgnoreCase("kid");
            out.add(p);
        }
        if (!out.isEmpty()) saveProfiles(c, out);
        return out;
    }

    static synchronized void saveProfiles(Context c, List<Profile> profiles) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Profile p : profiles) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", p.id == null || p.id.isEmpty() ? java.util.UUID.randomUUID().toString() : p.id);
                o.put("name", p.name);
                o.put("kid", p.kid);
                org.json.JSONArray d = new org.json.JSONArray();
                for (String pkg : p.dock) d.put(pkg);
                o.put("dock", d);
                org.json.JSONArray h = new org.json.JSONArray();
                for (String pkg : p.hidden) h.put(pkg);
                o.put("hidden", h);
                arr.put(o);
            }
            c.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                    .putString("user_profiles_json", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Hidden packages for profile idx, from that profile's own JSON object. */
    static Set<String> hiddenPkgs(Context c, int profileIdx) {
        List<Profile> ps = loadProfiles(c);
        if (profileIdx < 0 || profileIdx >= ps.size()) return new HashSet<>();
        return new HashSet<>(ps.get(profileIdx).hidden);
    }

    static synchronized void saveHidden(Context c, int profileIdx, Set<String> pkgs) {
        List<Profile> ps = loadProfiles(c);
        if (profileIdx < 0 || profileIdx >= ps.size()) return;
        ps.get(profileIdx).hidden.clear();
        ps.get(profileIdx).hidden.addAll(pkgs);
        saveProfiles(c, ps);
    }

    /**
     * One-time migration from the old shared "hidden_pkgs" key: whatever was
     * hidden before the per-profile change stays hidden for the profile that
     * is active when the new build first runs, and other profiles start
     * empty. The shared key is then removed so it can never leak again.
     */
    static void migrateLegacyHidden(Context c, int profileIdx) {
        android.content.SharedPreferences prefs =
                c.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (prefs.getBoolean("hidden_migrated", false)) return;
        String legacy = prefs.getString("hidden_pkgs", "");
        if (!legacy.trim().isEmpty()) {
            List<Profile> ps = loadProfiles(c);
            if (profileIdx >= 0 && profileIdx < ps.size()) {
                for (String pkg : legacy.split(",")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty()) ps.get(profileIdx).hidden.add(pkg);
                }
                saveProfiles(c, ps);
            }
        }
        prefs.edit().remove("hidden_pkgs").putBoolean("hidden_migrated", true).apply();
    }

    /** The dock for profile idx, from that profile's own JSON object. */
    static List<String> effectiveDock(Context c, int idx) {
        List<Profile> ps = loadProfiles(c);
        if (idx < 0 || idx >= ps.size()) return new ArrayList<>();
        return new ArrayList<>(ps.get(idx).dock);
    }

    static synchronized void saveDock(Context c, int idx, List<String> dock) {
        List<Profile> ps = loadProfiles(c);
        if (idx < 0 || idx >= ps.size()) return;
        ps.get(idx).dock.clear();
        ps.get(idx).dock.addAll(dock);
        saveProfiles(c, ps);
    }

    /** Dock packages for profile idx, from the user-added profiles (empty for a new user). */
    static List<String> profileDock(Context c, int idx) {
        return effectiveDock(c, idx);
    }

    /**
     * Dock apps come from the active profile; the grid is everything else (minus the
     * config-hidden list and the user's own hidden folder). gridOrder, when given,
     * pins the grid to the user's arranged order; anything new lands alphabetically after it.
     */
    static Result load(Context c, List<String> dockPkgs, Set<String> userHidden,
                       List<String> gridOrder) {
        return loadInternal(c, dockPkgs, userHidden, gridOrder, true);
    }

    private static Result loadInternal(Context c, List<String> dockPkgs, Set<String> userHidden,
                       List<String> gridOrder, boolean renderBanners) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, byPkg, Intent.CATEGORY_LAUNCHER, false);
        byPkg.remove(c.getPackageName());
        Set<String> hidden = new HashSet<>(Arrays.asList(
                c.getResources().getStringArray(R.array.hidden_packages)));
        hidden.addAll(userHidden);

        Result r = new Result();
        for (String pkg : dockPkgs) {
            if (hidden.contains(pkg)) continue;
            App a = byPkg.remove(pkg);
            if (a != null) r.dock.add(a);
        }
        List<App> rest = new ArrayList<>();
        for (App a : byPkg.values()) if (!hidden.contains(a.pkg)) rest.add(a);
        Collator col = Collator.getInstance();
        Collections.sort(rest, (x, y) -> col.compare(x.label, y.label));
        if (gridOrder != null && !gridOrder.isEmpty()) {
            Set<String> used = new HashSet<>();
            for (String pkg : gridOrder) {
                for (App a : rest) {
                    if (a.pkg.equals(pkg)) {
                        r.grid.add(a);
                        used.add(pkg);
                        break;
                    }
                }
            }
            for (App a : rest) if (!used.contains(a.pkg)) r.grid.add(a);
        } else {
            r.grid.addAll(rest);
        }

        if (renderBanners) {
            for (App a : r.dock) render(pm, a);
            for (App a : r.grid) render(pm, a);
        }
        return r;
    }

    /** Every launchable app, alphabetical, for the Hidden folder view. */
    static List<App> allApps(Context c) {
        return allAppsInternal(c, true);
    }

    private static List<App> allAppsInternal(Context c, boolean renderBanners) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, byPkg, Intent.CATEGORY_LAUNCHER, false);
        byPkg.remove(c.getPackageName());
        List<App> out = new ArrayList<>(byPkg.values());
        Collator col = Collator.getInstance();
        Collections.sort(out, (x, y) -> col.compare(x.label, y.label));
        if (renderBanners) for (App a : out) render(pm, a);
        return out;
    }

    /** Renders one app's banner, for the phone server's /api/banner endpoint.
     *  The server no longer holds a rendered copy of every app to serve one. */
    static Bitmap bannerFor(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(pm, byPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, true);
        collect(pm, byPkg, Intent.CATEGORY_LAUNCHER, false);
        App a = byPkg.get(pkg);
        if (a == null) return null;
        render(pm, a);
        return a.banner;
    }

    private static void collect(PackageManager pm, Map<String, App> out, String category, boolean leanback) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (out.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.component = new ComponentName(ai.packageName, ai.name);
            a.label = String.valueOf(ri.loadLabel(pm)).trim();
            a.leanback = leanback;
            out.put(a.pkg, a);
        }
    }

    private static void render(PackageManager pm, App a) {
        // One bad third-party banner must never kill the home app: any
        // exception here becomes a plain placeholder tile, not a crash.
        Bitmap b = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Drawable banner = null;
        try {
            ActivityInfo ai = pm.getActivityInfo(a.component, 0);
            banner = ai.loadBanner(pm);
            if (banner == null) banner = pm.getApplicationBanner(a.pkg);
            if (banner != null) {
                cv.drawColor(0xFF1E1E21);   // surface for banners with transparent areas
                banner.setBounds(0, 0, BANNER_W, BANNER_H);
                banner.draw(cv);
            } else {
                Drawable icon = ai.loadIcon(pm);
                int tint = sample(drawableBitmap(icon, 24, 24));
                float[] hsv = new float[3];
                Color.colorToHSV(tint, hsv);
                hsv[1] = Math.min(hsv[1], 0.6f);
                hsv[2] = Math.max(0.22f, Math.min(hsv[2], 0.32f));   // never vanish into the black page
                cv.drawColor(Color.HSVToColor(hsv));
                int s = BANNER_H * 56 / 100;
                int l = (BANNER_W - s) / 2, t = (BANNER_H - s) / 2;
                icon.setBounds(l, t, l + s, t + s);
                icon.draw(cv);
            }
        } catch (Exception ignored) {
            cv.drawColor(0xFF2C2C2E);
        }
        a.banner = b;
        try {
            a.glow = vivid(sample(Bitmap.createScaledBitmap(b, 32, 18, true)));
        } catch (Exception ignored) {
            a.glow = 0xFF2C2C2E;
        }
    }

    /** Same app list as load(), but with labels only and no banners rendered.
     *  Used by the phone server and the wizard pickers, which only need
     *  names: rendering every banner there was a second and third copy
     *  of every banner bitmap in RAM. */
    static Result loadLabelsOnly(Context c, List<String> dockPkgs, Set<String> userHidden,
                                 List<String> gridOrder) {
        return loadInternal(c, dockPkgs, userHidden, gridOrder, false);
    }

    static List<App> allAppsLabelsOnly(Context c) {
        return allAppsInternal(c, false);
    }

    private static Bitmap drawableBitmap(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    /** Average colour weighted towards saturated, bright pixels. */
    private static int sample(Bitmap b) {
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, wsum = 0;
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double w = 0.02 + hsv[1] * hsv[1] * hsv[2];
                r += Color.red(p) * w;
                g += Color.green(p) * w;
                bl += Color.blue(p) * w;
                wsum += w;
            }
        }
        if (wsum == 0) return 0xFF8AA4FF;
        return Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
    }

    private static int vivid(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.15f) {           // near-grey banner: soft cool white glow
            return 0xFFB8C4E0;
        }
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }

    static Intent launchIntent(PackageManager pm, App a) {
        Intent i = a.leanback ? pm.getLeanbackLaunchIntentForPackage(a.pkg) : null;
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
