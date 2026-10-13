package com.robot.navlearner;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

/**
 * MainActivity — Robot Navigation Control Panel
 *
 * Three navigation modes:
 * ──────────────────────────────────────────────────────────────
 * 1. GO TO POINT       → moveToLocation(point1Name)
 * 2. GO & RETURN       → moveToLocation(point2Name) → moveToLocation(home)
 * 3. 3-POINT TOUR      → point A → point B → point C → home (sequential)
 * ──────────────────────────────────────────────────────────────
 *
 * Point names are configured in SettingsActivity and stored in SharedPreferences.
 * They must EXACTLY match the names set in the robot's Deployment Environment app.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // ── UI references ────────────────────────────────────────────────────────
    private Button btnGoToPoint;
    private Button btnGoAndReturn;
    private Button btnTourStart;
    private Button btnSettings;
    private TextView tvStatus;
    private TextView tvStatusDetail;
    private CardView cardStatus;
    private View progressBar;

    // ── Robot controller ─────────────────────────────────────────────────────
    private RobotMovementController controller;

    // ── Activity flags ───────────────────────────────────────────────────────
    private boolean isNavigating = false;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupClickListeners();

        controller = new RobotMovementController();
        Log.d(TAG, "MainActivity ready. Robot URL: " + ConfigManager.getRobotWebSocketUrl());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh button labels every time we return from SettingsActivity
        refreshButtonLabels();
        setIdle("Ready — select a navigation mode");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (controller != null) {
            controller.cleanup();
        }
    }

    // ── View setup ───────────────────────────────────────────────────────────

    private void bindViews() {
        btnGoToPoint   = findViewById(R.id.btn_go_to_point);
        btnGoAndReturn = findViewById(R.id.btn_go_and_return);
        btnTourStart   = findViewById(R.id.btn_tour_start);
        btnSettings    = findViewById(R.id.btn_settings);
        tvStatus       = findViewById(R.id.tv_status);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        cardStatus     = findViewById(R.id.card_status);
        progressBar    = findViewById(R.id.progress_indicator);
    }

    private void refreshButtonLabels() {
        String p1    = ConfigManager.getPoint1Name();
        String p2    = ConfigManager.getPoint2Name();
        String home  = ConfigManager.getHomeName();
        String tA    = ConfigManager.getTourAName();
        String tB    = ConfigManager.getTourBName();
        String tC    = ConfigManager.getTourCName();

        btnGoToPoint.setText("Go to\n" + p1);
        btnGoAndReturn.setText("Go to " + p2 + "\n→ Return to " + home);
        btnTourStart.setText("3-Point Tour\n" + tA + " → " + tB + " → " + tC + " → " + home);
    }

    // ── Button click handlers ────────────────────────────────────────────────

    private void setupClickListeners() {
        btnGoToPoint.setOnClickListener(v -> onGoToPointClicked());
        btnGoAndReturn.setOnClickListener(v -> onGoAndReturnClicked());
        btnTourStart.setOnClickListener(v -> onTourClicked());
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });
    }

    /**
     * Mode 1: Simple go-to
     * Robot navigates to Point 1 and stays there.
     */
    private void onGoToPointClicked() {
        if (isNavigating) { showBusy(); return; }

        String destination = ConfigManager.getPoint1Name();
        Log.d(TAG, "Mode 1: Go to [" + destination + "]");
        setNavigating("Going to " + destination + "…");

        controller.moveToLocation(destination, new RobotMovementController.MovementCallback() {
            @Override public void onMovementStart() {
                setStatus("Moving to " + destination + "…", "Robot is on its way");
            }
            @Override public void onMovementComplete() {
                setIdle("✓ Arrived at " + destination);
                finishNavigation();
            }
            @Override public void onError(String error) {
                setError("Failed: " + error);
                finishNavigation();
            }
            @Override public void onStatusUpdate(String message) {
                setStatusDetail(message);
            }
        });
    }

    /**
     * Mode 2: Go to Point 2, then return home.
     * Sequential: destination → home
     */
    private void onGoAndReturnClicked() {
        if (isNavigating) { showBusy(); return; }

        String destination = ConfigManager.getPoint2Name();
        String home        = ConfigManager.getHomeName();
        Log.d(TAG, "Mode 2: Go to [" + destination + "] then return to [" + home + "]");
        setNavigating("Going to " + destination + "…");

        controller.moveToLocation(destination, new RobotMovementController.MovementCallback() {
            @Override public void onMovementStart() {
                setStatus("Moving to " + destination + "…", "Will return home after arrival");
            }
            @Override public void onMovementComplete() {
                Log.d(TAG, "Arrived at " + destination + " — now returning to " + home);
                setStatus("Returning to " + home + "…", "Phase 2/2");

                // Chain the return-to-home movement
                controller.moveToLocation(home, new RobotMovementController.MovementCallback() {
                    @Override public void onMovementStart() {
                        setStatus("Heading home…", "Returning to " + home);
                    }
                    @Override public void onMovementComplete() {
                        setIdle("✓ Returned to " + home);
                        finishNavigation();
                    }
                    @Override public void onError(String error) {
                        setError("Failed returning home: " + error);
                        finishNavigation();
                    }
                    @Override public void onStatusUpdate(String message) {
                        setStatusDetail(message);
                    }
                });
            }
            @Override public void onError(String error) {
                setError("Failed: " + error);
                finishNavigation();
            }
            @Override public void onStatusUpdate(String message) {
                setStatusDetail(message);
            }
        });
    }

    /**
     * Mode 3: Sequential 3-point tour + return home.
     * Navigates: Point A → Point B → Point C → Home
     * Each leg waits for confirmed arrival before starting the next.
     */
    private void onTourClicked() {
        if (isNavigating) { showBusy(); return; }

        String[] points = {
            ConfigManager.getTourAName(),
            ConfigManager.getTourBName(),
            ConfigManager.getTourCName(),
            ConfigManager.getHomeName()   // Final leg always returns home
        };

        Log.d(TAG, "Mode 3: Tour → " + String.join(" → ", points));
        setNavigating("Starting 3-point tour…");

        // Kick off the sequential chain starting at index 0
        navigateSequentially(points, 0);
    }

    /**
     * Recursive sequential navigation.
     * Navigates to points[index], then calls itself with index+1 on arrival.
     * Stops cleanly when all points are visited.
     */
    private void navigateSequentially(String[] points, int index) {
        if (index >= points.length) {
            // All points visited
            Log.d(TAG, "✓ Tour complete — all " + points.length + " points visited");
            setIdle("✓ Tour complete — returned to " + points[points.length - 1]);
            finishNavigation();
            return;
        }

        String destination = points[index];
        int legNumber      = index + 1;
        int totalLegs      = points.length;

        Log.d(TAG, "Tour leg " + legNumber + "/" + totalLegs + ": → " + destination);
        setStatus("Tour " + legNumber + "/" + totalLegs + ": Going to " + destination, "");

        controller.moveToLocation(destination, new RobotMovementController.MovementCallback() {
            @Override public void onMovementStart() {
                setStatus("Tour " + legNumber + "/" + totalLegs + ": Moving to " + destination,
                        "Robot is on its way");
            }
            @Override public void onMovementComplete() {
                Log.d(TAG, "Leg " + legNumber + " complete — arrived at " + destination);
                setStatusDetail("✓ Arrived at " + destination);
                // Immediately start the next leg
                navigateSequentially(points, index + 1);
            }
            @Override public void onError(String error) {
                setError("Tour failed at " + destination + ": " + error);
                finishNavigation();
            }
            @Override public void onStatusUpdate(String message) {
                setStatusDetail(message);
            }
        });
    }

    // ── State helpers ────────────────────────────────────────────────────────

    private void setNavigating(String message) {
        isNavigating = true;
        setButtonsEnabled(false);
        cardStatus.setCardBackgroundColor(getResources().getColor(R.color.status_navigating));
        tvStatus.setText(message);
        tvStatusDetail.setText("Waiting for robot…");
        progressBar.setVisibility(View.VISIBLE);
    }

    private void setStatus(String title, String detail) {
        tvStatus.setText(title);
        tvStatusDetail.setText(detail);
    }

    private void setStatusDetail(String detail) {
        tvStatusDetail.setText(detail);
    }

    private void setIdle(String message) {
        tvStatus.setText(message);
        tvStatusDetail.setText("");
        cardStatus.setCardBackgroundColor(getResources().getColor(R.color.status_idle));
        progressBar.setVisibility(View.GONE);
        setButtonsEnabled(true);
    }

    private void setError(String message) {
        tvStatus.setText(message);
        tvStatusDetail.setText("Check logcat for details");
        cardStatus.setCardBackgroundColor(getResources().getColor(R.color.status_error));
        progressBar.setVisibility(View.GONE);
        Log.e(TAG, "Navigation error: " + message);
    }

    private void finishNavigation() {
        isNavigating = false;
        setButtonsEnabled(true);
        progressBar.setVisibility(View.GONE);
    }

    private void setButtonsEnabled(boolean enabled) {
        btnGoToPoint.setEnabled(enabled);
        btnGoAndReturn.setEnabled(enabled);
        btnTourStart.setEnabled(enabled);
        float alpha = enabled ? 1.0f : 0.5f;
        btnGoToPoint.setAlpha(alpha);
        btnGoAndReturn.setAlpha(alpha);
        btnTourStart.setAlpha(alpha);
    }

    private void showBusy() {
        setStatusDetail("Robot is busy — wait for it to finish");
    }
}
