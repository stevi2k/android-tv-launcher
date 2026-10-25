package com.kulwant.atvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared backend for the setup wizard, whether it is driven from the TV
 * (WizardActivity, opened from the Launcher Settings tile) or from a phone
 * (the wizard webpage hosted by PhoneServer on port 8080).
 *
 * Debloat and set-home go through LocalShell, the TV's own localhost ADB
 * connection, so this is one install only: no PC, no second helper app.
 * Everything else (users, docks, hidden, backup) is plain app code and
 * works even if local ADB is not authorised yet.
 */
final class SetupManager {

    /** A debloat candidate: label, package, and whether it is on by default. */
    static final class Candidate {
        final String label, pkg;
        final boolean onByDefault;
        Candidate(String label, String pkg, boolean onByDefault) {
            this.label = label;
            this.pkg = pkg;
            this.onByDefault = onByDefault;
        }
    }

    static final List<Candidate> CANDIDATES = new ArrayList<>();
    static {
        CANDIDATES.add(new Candidate("YouTube Music", "com.google.android.youtube.tvmusic", true));
        CANDIDATES.add(new Candidate("Google TV videos app", "com.google.android.videos", true));
        CANDIDATES.add(new Candidate("Samba TV telemetry", "tv.samba.ssm", true));
        CANDIDATES.add(new Candidate("Sony promos", "com.sony.dtv.promos", true));
        CANDIDATES.add(new Candidate("Sony Select", "com.sony.dtv.sonyselect", true));
        CANDIDATES.add(new Candidate("Sony demo support", "com.sony.dtv.demosupport", true));
        CANDIDATES.add(new Candidate("Sony demo system", "com.sony.dtv.demosystemsupport", true));
        CANDIDATES.add(new Candidate("Sony multi-screen demo", "com.sony.dtv.multiscreendemo", true));
        CANDIDATES.add(new Candidate("MediaTek logdump", "com.mediatek.tv.oneworld.logdump", true));
        CANDIDATES.add(new Candidate("MediaTek logdump service", "com.mediatek.tv.oneworld.logdumpservice", true));
        CANDIDATES.add(new Candidate("MediaTek game", "com.mediatek.tv.game", true));
        CANDIDATES.add(new Candidate("MediaTek app lock", "com.mediatek.tv.applock", true));
        CANDIDATES.add(new Candidate("MediaTek simple remote", "com.mediatek.tv.simpleremote", true));
        CANDIDATES.add(new Candidate("MediaTek diagnosis", "com.mediatek.tv.diagnosis", true));
        CANDIDATES.add(new Candidate("Play Games", "com.google.android.play.games", true));
        CANDIDATES.add(new Candidate("Google feedback", "com.google.android.feedback", true));
        CANDIDATES.add(new Candidate("ATSC tuner (no antenna only)", "com.mediatek.dtv.tvinput.atsctuner", false));
        CANDIDATES.add(new Candidate("YouView", "com.youview.poa", false));
        CANDIDATES.add(new Candidate("ISDB tuner", "com.mediatek.dtv.tvinput.isdbtuner", false));
        CANDIDATES.add(new Candidate("DVB tuner", "com.mediatek.dtv.tvinput.dvbtuner", false));
    }

    /** Packages the wizard must never offer to disable. */
    static final Set<String> PROTECTED = new HashSet<>();
    static {
        PROTECTED.add("com.kulwant.atvhome");
        PROTECTED.add("moe.shizuku.privileged.api");
        PROTECTED.add("com.google.android.katniss");
        PROTECTED.add("com.android.settings");
        PROTECTED.add("com.android.systemui");
        PROTECTED.add("com.google.android.packageinstaller");
        PROTECTED.add("com.android.vending");
        PROTECTED.add("com.google.android.gms");
        PROTECTED.add("com.google.android.tv.remote.service");
        // Core Android pieces: disabling these can brick the home screen or input.
        PROTECTED.add("android");
        PROTECTED.add("com.android.shell");
        PROTECTED.add("com.android.providers.settings");
        PROTECTED.add("com.android.providers.media");
        PROTECTED.add("com.android.inputdevices");
        PROTECTED.add("com.android.bluetooth");
        PROTECTED.add("com.google.android.inputmethod.latin");
        PROTECTED.add("com.google.android.tv");
        PROTECTED.add("com.google.android.apps.tv.launcherx");
        PROTECTED.add("com.google.android.tvlauncher");
        // Audit additions: TV settings, WebView, TV provider, Google services.
        PROTECTED.add("com.android.tv.settings");
        PROTECTED.add("com.google.android.webview");
        PROTECTED.add("com.android.webview");
        PROTECTED.add("com.android.providers.tv");
        PROTECTED.add("com.google.android.gsf");
    }

    private final Context c;
    private final SharedPreferences prefs;

    /** A package name is only ever interpolated into a shell command if it
     *  matches this shape AND is actually installed. Anything else (for
     *  example "x; rm -rf") is rejected before it reaches ADB, so a paired
     *  phone cannot inject shell commands through debloat, restore or
     *  set-home. */
    static boolean isValidPackage(Context c, String pkg) {
        if (pkg == null || !pkg.matches("^[A-Za-z][\\w]*(\\.[\\w]+)+$")) return false;
        try {
            return c.getPackageManager().getApplicationInfo(pkg, 0) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean isValidPackage(String pkg) {
        return isValidPackage(c, pkg);
    }

    SetupManager(Context c) {
        this.c = c.getApplicationContext();
        prefs = this.c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- profiles

    static final class ProfileInfo {
        String name;
        boolean kid;
        List<String> dock = new ArrayList<>();
    }

    /** Profiles the user added (JSON in prefs). No presets: an empty list is valid. */
    List<ProfileInfo> profiles() {
        List<ProfileInfo> out = new ArrayList<>();
        for (AppRepo.Profile ap : AppRepo.loadProfiles(c)) {
            ProfileInfo p = new ProfileInfo();
            p.name = ap.name;
            p.dock.addAll(ap.dock);
            p.kid = ap.kid;
            out.add(p);
        }
        return out;
    }

    /** Adds a user with an empty dock; the user then picks the dock in Settings / wizard. */
    int addProfile(String name, boolean kid) {
        if (name == null || name.trim().isEmpty()) return -1;
        List<AppRepo.Profile> ps = AppRepo.loadProfiles(c);
        AppRepo.Profile p = new AppRepo.Profile();
        p.id = java.util.UUID.randomUUID().toString();
        p.name = name.trim();
        p.kid = kid;
        ps.add(p);
        AppRepo.saveProfiles(c, ps);
        return ps.size() - 1;
    }

    void setKid(int idx, boolean kid) {
        List<AppRepo.Profile> ps = AppRepo.loadProfiles(c);
        if (idx < 0 || idx >= ps.size()) return;
        ps.get(idx).kid = kid;
        AppRepo.saveProfiles(c, ps);
    }

    void renameProfile(int idx, String name) {
        List<AppRepo.Profile> ps = AppRepo.loadProfiles(c);
        if (idx < 0 || idx >= ps.size() || name == null || name.trim().isEmpty()) return;
        ps.get(idx).name = name.trim();
        AppRepo.saveProfiles(c, ps);
    }

    /**
     * Deletes a user. This is safe precisely because profiles are JSON objects:
     * removing one object removes that user's dock and hidden list with it,
     * and every other user's object is untouched, so nothing shifts onto
     * someone else. Only the active-profile index needs adjusting: if the
     * deleted user was active, fall back to the first user; if the active
     * user was after the deleted one, shift it down one so it still points
     * at the same person.
     */
    boolean deleteProfile(int idx) {
        List<AppRepo.Profile> ps = AppRepo.loadProfiles(c);
        if (idx < 0 || idx >= ps.size()) return false;
        ps.remove(idx);
        AppRepo.saveProfiles(c, ps);
        int active = prefs.getInt("profile", 0);
        if (ps.isEmpty()) active = 0;
        else if (active == idx) active = 0;
        else if (active > idx) active = active - 1;
        prefs.edit().putInt("profile", active).apply();
        return true;
    }

    List<String> dockFor(int idx) {
        return AppRepo.effectiveDock(c, idx);
    }

    void saveDock(int idx, List<String> pkgs) {
        AppRepo.saveDock(c, idx, pkgs);
    }

    Set<String> hiddenFor(int idx) {
        return AppRepo.hiddenPkgs(c, idx);
    }

    void saveHidden(int idx, Set<String> pkgs) {
        AppRepo.saveHidden(c, idx, pkgs);
    }

    // ---------------------------------------------------------------- weather location

    /** Saved weather place name, or "" if none was set. */
    String weatherPlace() {
        return prefs.getString("weather_place", "");
    }

    void setWeatherLocation(double lat, double lon, String place) {
        prefs.edit().putString("weather_lat", String.valueOf(lat))
                .putString("weather_lon", String.valueOf(lon))
                .putString("weather_place", place == null ? "" : place).apply();
    }

    void clearWeatherLocation() {
        prefs.edit().remove("weather_lat").remove("weather_lon").remove("weather_place").apply();
    }

    private static String httpGet(String url) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Turns typed text into a saved location. "lat,lon" is used as-is; anything
     * else is geocoded with Open-Meteo (free, no key). Returns the place name,
     * or null if nothing matched.
     */
    String setWeatherFromText(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = text.trim();
        try {
            // "Austin, TX" and "Paris, France" also split on a comma, so a
            // failed lat/lon parse must fall through to geocoding, not fail.
            String[] ll = t.split(",");
            if (ll.length == 2) {
                try {
                    double lat = Double.parseDouble(ll[0].trim()), lon = Double.parseDouble(ll[1].trim());
                    setWeatherLocation(lat, lon, t);
                    return t;
                } catch (NumberFormatException ignored) {
                    // not coordinates: geocode below
                }
            }
            String json = httpGet("https://geocoding-api.open-meteo.com/v1/search?name="
                    + java.net.URLEncoder.encode(t, "UTF-8") + "&count=1&language=en&format=json");
            org.json.JSONArray res = new org.json.JSONObject(json).optJSONArray("results");
            if (res == null || res.length() == 0) return null;
            org.json.JSONObject r = res.getJSONObject(0);
            String place = r.optString("name", t);
            if (!r.optString("admin1", "").isEmpty()) place += ", " + r.optString("admin1");
            if (!r.optString("country", "").isEmpty()) place += ", " + r.optString("country");
            setWeatherLocation(r.getDouble("latitude"), r.getDouble("longitude"), place);
            return place;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Approximate location from the TV's public IP (city level, via ip-api).
     * This is the fallback when no location was typed in: it sends the public
     * IP to ip-api, nothing else, and saves the city it returns. Returns the
     * place name, or null on failure.
     */
    String detectWeatherFromIp() {
        // ip-api's free endpoint is http only: https returns 403 (Pro only),
        // and Android 9+ blocks cleartext http unless the manifest allows it,
        // which is why Detect used to fail silently on the TV while curl
        // worked. Try http first, then https, then ipapi.co over https.
        String[] urls = {
            "http://ip-api.com/json?fields=status,lat,lon,city",
            "https://ip-api.com/json?fields=status,lat,lon,city",
            "https://ipapi.co/json/",
        };
        for (String url : urls) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(httpGet(url));
                if (!"success".equals(o.optString("status")) && !o.has("city")) continue;
                String place = o.optString("city", "");
                if (place.isEmpty()) continue;
                double lat = o.has("lat") ? o.getDouble("lat") : o.getDouble("latitude");
                double lon = o.has("lon") ? o.getDouble("lon") : o.getDouble("longitude");
                setWeatherLocation(lat, lon, place);
                return place;
            } catch (Exception ignored) {
                // try the next source
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- apps

    /** All launchable apps, sorted by label, with dock/hidden state removed later by callers. */
    List<AppRepo.App> allApps() {
        return AppRepo.allApps(c);
    }

    /** Same list, labels only, no banners: for wizard pickers and the phone server. */
    List<AppRepo.App> allAppsLabelsOnly() {
        return AppRepo.allAppsLabelsOnly(c);
    }

    /** Every installed package that is currently disabled, sorted by label.
     *  This is what the Restore screen shows, so the user can bring back
     *  one app at a time, not just all-or-nothing. It includes packages
     *  disabled by the wizard, by the earlier PC debloat, and by the
     *  manufacturer, because all of them are disabled the same way. */
    List<Candidate> disabledApps() {
        PackageManager pm = c.getPackageManager();
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.enabled || seen.contains(ai.packageName)) continue;
            seen.add(ai.packageName);
            String label = String.valueOf(ai.loadLabel(pm));
            if (label == null || label.isEmpty() || label.equals(ai.packageName)) label = ai.packageName;
            out.add(new Candidate(label, ai.packageName, false));
        }
        // Packages in the wizard log that PackageManager does not report as
        // disabled (for example a user-disabled package) still show up.
        for (String pkg : prefs.getString("wizard_disabled_log", "").split(",")) {
            pkg = pkg.trim();
            if (pkg.isEmpty() || seen.contains(pkg)) continue;
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (!ai.enabled) {
                    seen.add(pkg);
                    out.add(new Candidate(String.valueOf(ai.loadLabel(pm)), pkg, false));
                }
            } catch (Exception ignored) {
            }
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** Every installed package that is currently enabled and not protected,
     *  sorted by label. This is the "all apps and services" debloat list:
     *  launchable apps plus services and system apps the preset junk list
     *  does not cover. Protected core packages are never offered. */
    List<Candidate> enabledAllPackages() {
        PackageManager pm = c.getPackageManager();
        List<Candidate> out = new ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (!ai.enabled || PROTECTED.contains(ai.packageName)) continue;
            String label = String.valueOf(ai.loadLabel(pm));
            if (label == null || label.isEmpty()) label = ai.packageName;
            out.add(new Candidate(label, ai.packageName, false));
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** Candidates that are actually installed and currently enabled on this TV. */
    List<Candidate> installedCandidates() {
        PackageManager pm = c.getPackageManager();
        List<Candidate> out = new ArrayList<>();
        for (Candidate cand : CANDIDATES) {
            try {
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(cand.pkg, 0);
                if (ai.enabled) out.add(cand);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** HOME activities other than our own launcher, for the set-home step. */
    List<Map<String, String>> homePackages() {
        List<Map<String, String>> out = new ArrayList<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("pkg", pkg);
            m.put("label", String.valueOf(ri.loadLabel(c.getPackageManager())));
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- backup / restore

    /** Writes a timestamped backup file and returns its path, or an error
     *  message. Timestamped, so running debloat twice never replaces the
     *  pre-debloat state. Note: this file is a record, not a restore
     *  mechanism; restore uses the wizard disabled log and the Restore
     *  screen. The writer is closed even on error. */
    String backup() {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            File f = new File(dir, "launcher-backup-" + stamp + ".txt");
            StringBuilder sb = new StringBuilder();
            sb.append("launcher backup\n");
            sb.append("disabled: ").append(prefs.getString("wizard_disabled_log", "")).append("\n");
            List<ProfileInfo> ps = profiles();
            for (int i = 0; i < ps.size(); i++) {
                sb.append("profile ").append(i).append(" name=").append(ps.get(i).name)
                        .append(" dock=").append(join(dockFor(i)))
                        .append(" hidden=").append(join(new ArrayList<>(hiddenFor(i)))).append("\n");
            }
            sb.append("packages:\n");
            for (android.content.pm.ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
                sb.append(ai.packageName).append(" enabled=").append(ai.enabled).append("\n");
            }
            try (FileWriter w = new FileWriter(f)) {
                w.write(sb.toString());
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "backup failed: " + e.getMessage();
        }
    }

    /** Re-enables one package, via local ADB, and drops it from the wizard log. */
    String restoreOne(LocalShell shell, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "No app selected.";
        if (!isValidPackage(pkg)) return "Invalid package, not restored.";
        try {
            shell.run("pm enable --user 0 " + pkg);
            Set<String> disabled = new HashSet<>();
            for (String s : prefs.getString("wizard_disabled_log", "").split(",")) {
                if (!s.trim().isEmpty() && !s.trim().equals(pkg)) disabled.add(s.trim());
            }
            prefs.edit().putString("wizard_disabled_log", join(new ArrayList<>(disabled))).apply();
            return pkg + ": restored";
        } catch (Exception e) {
            return pkg + ": failed: " + shell.lastError();
        }
    }

    /** Re-enables every package in the wizard disabled log, via local ADB. */
    String restore(LocalShell shell) {
        String log = prefs.getString("wizard_disabled_log", "");
        if (log.isEmpty()) return "Nothing to restore: no packages were disabled by the wizard.";
        StringBuilder result = new StringBuilder();
        java.util.List<String> pkgs = new java.util.ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String pkg : log.split(",")) {
            pkg = pkg.trim();
            if (pkg.isEmpty() || done.contains(pkg)) continue;
            done.add(pkg);
            if (!isValidPackage(pkg)) continue;  // uninstalled since: nothing to re-enable
            pkgs.add(pkg);
        }
        // One ADB connection for the whole restore, not one per app.
        java.util.List<String> cmds = new java.util.ArrayList<>();
        for (String pkg : pkgs) cmds.add("pm enable --user 0 " + pkg);
        java.util.List<String> outs = shell.runBatch(cmds);
        for (int i = 0; i < pkgs.size(); i++) {
            String o = outs.get(i);
            result.append(pkgs.get(i)).append(o.startsWith("failed:") ? ": " + o : ": re-enabled").append("\n");
        }
        prefs.edit().remove("wizard_disabled_log").apply();
        return result.toString();
    }

    // ---------------------------------------------------------------- debloat / home

    /** Disables the given packages via local ADB, optionally force-stopping first. */
    String debloat(LocalShell shell, List<String> pkgs, boolean stopFirst) {
        if (pkgs.isEmpty()) return "No apps selected.";
        StringBuilder result = new StringBuilder();
        Set<String> disabled = new HashSet<>();
        for (String s : prefs.getString("wizard_disabled_log", "").split(",")) {
            if (!s.trim().isEmpty()) disabled.add(s.trim());
        }
        // Batch the whole list over one ADB connection: force-stop then
        // disable, per app, in order, with per-app results kept separate.
        java.util.List<String> todo = new java.util.ArrayList<>();
        java.util.List<String> cmds = new java.util.ArrayList<>();
        for (String pkg : pkgs) {
            if (!isValidPackage(pkg)) {
                result.append(pkg).append(": skipped (invalid package)\n");
                continue;
            }
            if (PROTECTED.contains(pkg)) {
                result.append(pkg).append(": skipped (protected)\n");
                continue;
            }
            todo.add(pkg);
            if (stopFirst) cmds.add("am force-stop " + pkg);
            cmds.add("pm disable-user --user 0 " + pkg);
        }
        java.util.List<String> outs = shell.runBatch(cmds);
        int oi = 0;
        for (String pkg : todo) {
            if (stopFirst) {
                String stop = outs.get(oi++);
                if (stop.startsWith("failed:")) {
                    oi++; // skip this app's disable slot too, keep the rest aligned
                    result.append(pkg).append(": failed: ").append(stop).append("\n");
                    continue;
                }
            }
            String out = outs.get(oi++);
            if (out.startsWith("failed:")) {
                result.append(pkg).append(": failed: ").append(out).append("\n");
                continue;
            }
            result.append(pkg).append(": disabled");
            if (!out.isEmpty()) result.append(" (").append(out).append(")");
            result.append("\n");
            disabled.add(pkg);
        }
        prefs.edit().putString("wizard_disabled_log", join(new ArrayList<>(disabled))).apply();
        return result.toString();
    }

    /** Disables a stock home package via local ADB, so this launcher becomes home.
     *  Set-home is no longer a bypass around the protected list: the package
     *  must be one of the HOME activities actually returned by
     *  homePackages(), so systemui and other non-home packages cannot be
     *  disabled through this route. */
    String setHome(LocalShell shell, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(c.getPackageName()))
            return "Pick a stock home app to disable, not this launcher.";
        boolean isHome = false;
        for (Map<String, String> h : homePackages()) {
            if (pkg.equals(h.get("pkg"))) { isHome = true; break; }
        }
        if (!isHome) return "That package is not a home app on this TV.";
        try {
            shell.run("pm disable-user --user 0 " + pkg);
            Set<String> disabled = new HashSet<>();
            for (String s : prefs.getString("wizard_disabled_log", "").split(",")) {
                if (!s.trim().isEmpty()) disabled.add(s.trim());
            }
            disabled.add(pkg);
            prefs.edit().putString("wizard_disabled_log", join(new ArrayList<>(disabled))).apply();
            return pkg + ": disabled, this launcher should now be home. If the TV asks which home app to use, pick this launcher and Always.";
        } catch (Exception e) {
            return pkg + ": failed: " + shell.lastError();
        }
    }

    static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }
}
