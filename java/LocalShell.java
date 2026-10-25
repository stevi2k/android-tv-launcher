package com.kulwant.atvhome;

import android.content.Context;

import java.io.File;
import java.util.Locale;

import dadb.AdbKeyPair;
import dadb.Dadb;

/**
 * Runs shell commands on the TV itself, through the TV's own localhost ADB
 * (127.0.0.1:5555), using the same keypair the phone remote already uses.
 *
 * This is the one-install path: no second helper app, no PC, no Shizuku.
 * The TV must have network debugging on, and the first connection asks
 * "Allow USB debugging?" on the TV, tick Always allow and press OK.
 * After that, this shell runs as the ADB shell user, exactly like a PC
 * ADB session, so it can run pm disable-user, am force-stop, etc.
 * A normal app cannot do those things on its own, this local ADB
 * connection is what makes the wizard possible.
 */
final class LocalShell {

    private final Context c;
    private String lastError;

    LocalShell(Context c) {
        this.c = c.getApplicationContext();
    }

    String lastError() {
        return lastError;
    }

    /** True if the local ADB daemon answers, even if the key is not authorised yet. */
    boolean ready() {
        try {
            run("echo ok");
            return true;
        } catch (Exception e) {
            lastError = describe(e);
            return false;
        }
    }

    /**
     * Runs several commands over ONE ADB connection, in order, and returns
     * one output per command (a failed command is reported in its own slot,
     * the rest still run). Debloating 20 apps used to open 40 separate
     * connections (force-stop + disable each); batching is the same work
     * with one connect and one disconnect.
     */
    java.util.List<String> runBatch(java.util.List<String> cmds) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        try {
            if (!priv.isFile() || !pub.isFile()) AdbKeyPair.Companion.generate(priv, pub);
            AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
            Dadb adb = Dadb.Companion.create("127.0.0.1", 5555, keys, 5_000, 0);
            try {
                for (String cmd : cmds) {
                    try {
                        String o = adb.shell(cmd).getAllOutput();
                        out.add(o == null ? "" : o.trim());
                    } catch (Exception e) {
                        out.add("failed: " + describe(e));
                    }
                }
                lastError = null;
            } finally {
                try {
                    adb.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            lastError = describe(e);
            while (out.size() < cmds.size()) out.add("failed: " + lastError);
        }
        return out;
    }

    /** Runs one shell command and returns its output (stdout + stderr, trimmed). */
    String run(String cmd) throws Exception {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        if (!priv.isFile() || !pub.isFile()) AdbKeyPair.Companion.generate(priv, pub);
        AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
        Dadb adb = Dadb.Companion.create("127.0.0.1", 5555, keys, 5_000, 0);
        try {
            String out = adb.shell(cmd).getAllOutput();
            lastError = null;
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            lastError = describe(e);
            throw e;
        } finally {
            try {
                adb.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String describe(Exception e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
        if (m.contains("refused")) return "Network debugging is off on the TV";
        if (m.contains("auth") || m.contains("timed out") || m.contains("timeout"))
            return "Accept \"Allow USB debugging\" on the TV (tick Always allow)";
        return "Couldn't reach the TV's debugging service: " + e.getMessage();
    }
}
