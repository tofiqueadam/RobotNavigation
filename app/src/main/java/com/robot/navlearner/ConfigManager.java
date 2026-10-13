package com.robot.navlearner;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * ConfigManager — loads robot connection settings from SharedPreferences,
 * with hardcoded defaults that match the physical robot on the BoA network.
 *
 * Settings are written by SettingsActivity and read here at runtime.
 *
 * Default Robot WebSocket URL: ws://192.168.20.22:9090
 * (rosbridge server running on the Yutong robot)
 */
public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREFS_NAME = "navlearner_config";

    // ── SharedPreference keys ────────────────────────────────────────────────
    public static final String KEY_ROBOT_IP   = "robot_ip";
    public static final String KEY_ROBOT_PORT = "robot_port";

    // Point name keys
    public static final String KEY_POINT1_NAME  = "point1_name";
    public static final String KEY_POINT2_NAME  = "point2_name";
    public static final String KEY_TOUR_A_NAME  = "tour_a_name";
    public static final String KEY_TOUR_B_NAME  = "tour_b_name";
    public static final String KEY_TOUR_C_NAME  = "tour_c_name";
    public static final String KEY_HOME_NAME    = "home_name";

    // ── Hard-coded defaults ──────────────────────────────────────────────────
    private static final String DEFAULT_ROBOT_IP   = "192.168.20.22";
    private static final String DEFAULT_ROBOT_PORT = "9090";
    private static final String DEFAULT_HOME       = "home";

    private static SharedPreferences prefs;

    public static void initialize(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Log.d(TAG, "ConfigManager initialised. Robot URL: " + getRobotWebSocketUrl());
    }

    // ── Robot connection ─────────────────────────────────────────────────────

    public static String getRobotIp() {
        return prefs.getString(KEY_ROBOT_IP, DEFAULT_ROBOT_IP);
    }

    public static String getRobotPort() {
        return prefs.getString(KEY_ROBOT_PORT, DEFAULT_ROBOT_PORT);
    }

    /** Returns the full rosbridge WebSocket URL, e.g. ws://192.168.20.22:9090 */
    public static String getRobotWebSocketUrl() {
        return "ws://" + getRobotIp() + ":" + getRobotPort();
    }

    // ── Point names ──────────────────────────────────────────────────────────

    public static String getPoint1Name() {
        return prefs.getString(KEY_POINT1_NAME, "machines_area");
    }

    public static String getPoint2Name() {
        return prefs.getString(KEY_POINT2_NAME, "waiting_area");
    }

    public static String getTourAName() {
        return prefs.getString(KEY_TOUR_A_NAME, "machines_area");
    }

    public static String getTourBName() {
        return prefs.getString(KEY_TOUR_B_NAME, "waiting_area");
    }

    public static String getTourCName() {
        return prefs.getString(KEY_TOUR_C_NAME, "back_office");
    }

    public static String getHomeName() {
        return prefs.getString(KEY_HOME_NAME, DEFAULT_HOME);
    }

    // ── Save ────────────────────────────────────────────────────────────────

    public static void saveAll(String robotIp, String robotPort,
                                String p1, String p2,
                                String tA, String tB, String tC,
                                String home) {
        prefs.edit()
                .putString(KEY_ROBOT_IP,   robotIp.trim())
                .putString(KEY_ROBOT_PORT, robotPort.trim())
                .putString(KEY_POINT1_NAME, p1.trim())
                .putString(KEY_POINT2_NAME, p2.trim())
                .putString(KEY_TOUR_A_NAME, tA.trim())
                .putString(KEY_TOUR_B_NAME, tB.trim())
                .putString(KEY_TOUR_C_NAME, tC.trim())
                .putString(KEY_HOME_NAME,  home.trim())
                .apply();
        Log.d(TAG, "Settings saved. Robot URL: ws://" + robotIp + ":" + robotPort);
    }
}
