package com.robot.navlearner;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * SettingsActivity — Configure all point names and robot connection.
 *
 * All settings are persisted to SharedPreferences via ConfigManager.
 * Changes take effect immediately — no restart required.
 *
 * IMPORTANT: Point names must EXACTLY match what is configured in the
 * robot's built-in "Deployment Environment" app (case-sensitive).
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText etRobotIp;
    private EditText etRobotPort;
    private EditText etPoint1;
    private EditText etPoint2;
    private EditText etTourA;
    private EditText etTourB;
    private EditText etTourC;
    private EditText etHome;
    private Button btnSave;
    private Button btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        bindViews();
        loadCurrentValues();

        btnSave.setOnClickListener(v -> saveSettings());
        btnBack.setOnClickListener(v -> finish());
    }

    private void bindViews() {
        etRobotIp   = findViewById(R.id.et_robot_ip);
        etRobotPort = findViewById(R.id.et_robot_port);
        etPoint1    = findViewById(R.id.et_point1);
        etPoint2    = findViewById(R.id.et_point2);
        etTourA     = findViewById(R.id.et_tour_a);
        etTourB     = findViewById(R.id.et_tour_b);
        etTourC     = findViewById(R.id.et_tour_c);
        etHome      = findViewById(R.id.et_home);
        btnSave     = findViewById(R.id.btn_save);
        btnBack     = findViewById(R.id.btn_back);
    }

    private void loadCurrentValues() {
        etRobotIp.setText(ConfigManager.getRobotIp());
        etRobotPort.setText(ConfigManager.getRobotPort());
        etPoint1.setText(ConfigManager.getPoint1Name());
        etPoint2.setText(ConfigManager.getPoint2Name());
        etTourA.setText(ConfigManager.getTourAName());
        etTourB.setText(ConfigManager.getTourBName());
        etTourC.setText(ConfigManager.getTourCName());
        etHome.setText(ConfigManager.getHomeName());
    }

    private void saveSettings() {
        String ip    = etRobotIp.getText().toString().trim();
        String port  = etRobotPort.getText().toString().trim();
        String p1    = etPoint1.getText().toString().trim();
        String p2    = etPoint2.getText().toString().trim();
        String tA    = etTourA.getText().toString().trim();
        String tB    = etTourB.getText().toString().trim();
        String tC    = etTourC.getText().toString().trim();
        String home  = etHome.getText().toString().trim();

        // Basic validation
        if (ip.isEmpty() || port.isEmpty()) {
            Toast.makeText(this, "Robot IP and port are required", Toast.LENGTH_SHORT).show();
            return;
        }
        if (p1.isEmpty() || p2.isEmpty() || tA.isEmpty() || tB.isEmpty() || tC.isEmpty() || home.isEmpty()) {
            Toast.makeText(this, "All point names are required", Toast.LENGTH_SHORT).show();
            return;
        }

        ConfigManager.saveAll(ip, port, p1, p2, tA, tB, tC, home);
        Toast.makeText(this, "Settings saved ✓", Toast.LENGTH_SHORT).show();
        finish();
    }
}
