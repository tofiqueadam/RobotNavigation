package com.robot.navlearner;

import android.app.Application;
import android.util.Log;

/**
 * Application class — initialises ConfigManager once at startup.
 */
public class NavLearnerApp extends Application {
    private static final String TAG = "NavLearnerApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "NavLearner app starting");
        ConfigManager.initialize(this);
        Log.d(TAG, "Robot WS URL: " + ConfigManager.getRobotWebSocketUrl());
    }
}
