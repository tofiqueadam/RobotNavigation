package com.robot.navlearner;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * RobotMovementController — the core SDK wrapper.
 *
 * HOW IT WORKS (rosbridge protocol v4.7.6):
 * ─────────────────────────────────────────
 * 1. Open a WebSocket connection to ws://<robot-ip>:9090  (rosbridge server)
 * 2. Subscribe to /navi_status and /robot_status topics
 * 3. Call the /poi_id ROS service with the destination point name
 * 4. Wait for:
 *      a. service_response with result=true, values.success=true  → command accepted
 *      b. /navi_status publish with msg.status=3                  → ARRIVED ✅
 * 5. On arrival, invoke the onComplete callback
 *
 * USAGE EXAMPLE — Simple go-to:
 * ─────────────────────────────
 *   controller.moveToLocation("waiting_area", new MovementCallback() {
 *       public void onMovementStart() { showStatus("Robot moving..."); }
 *       public void onMovementComplete() { showStatus("Arrived!"); }
 *       public void onError(String e) { showStatus("Error: " + e); }
 *   });
 *
 * USAGE EXAMPLE — Go & Return:
 * ────────────────────────────
 *   controller.moveToLocation("waiting_area", new MovementCallback() {
 *       public void onMovementStart() { showStatus("Going to point..."); }
 *       public void onMovementComplete() {
 *           // Now go home
 *           controller.moveToLocation("home", homeCallback);
 *       }
 *       public void onError(String e) { showStatus("Error: " + e); }
 *   });
 *
 * USAGE EXAMPLE — 3-point sequential tour:
 * ─────────────────────────────────────────
 *   navigateSequentially(new String[]{"machines_area", "waiting_area", "back_office", "home"}, 0);
 *
 *   void navigateSequentially(String[] points, int index) {
 *       if (index >= points.length) return;
 *       controller.moveToLocation(points[index], new MovementCallback() {
 *           public void onMovementStart() { showStatus("Going to " + points[index]); }
 *           public void onMovementComplete() { navigateSequentially(points, index + 1); }
 *           public void onError(String e) { showStatus("Error at " + points[index] + ": " + e); }
 *       });
 *   }
 */
public class RobotMovementController {

    private static final String TAG = "RobotMovController";

    // Arrival timeout — if robot doesn't arrive in this time, report error
    private static final long ARRIVAL_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(600);

    // ── Callback interfaces ──────────────────────────────────────────────────

    /**
     * Full movement lifecycle callback.
     * All callbacks are dispatched on the main (UI) thread.
     */
    public interface MovementCallback {
        /** Called when the robot accepts the navigation command and starts moving */
        void onMovementStart();
        /** Called when the robot confirms arrival at the destination */
        void onMovementComplete();
        /** Called on any error (connection failure, rejection, timeout, etc.) */
        void onError(String error);

        /** Optional: receive intermediate status strings for UI display */
        default void onStatusUpdate(String message) {}
    }

    // ── Internal state ───────────────────────────────────────────────────────

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient httpClient;
    private WebSocket webSocket;

    private volatile boolean isConnected = false;
    private volatile boolean movementCompleted = true;     // true = idle, false = in-flight
    private volatile String  currentMovementId = null;
    private volatile boolean movementCommandSent     = false;
    private volatile boolean movementCommandAccepted = false;
    private volatile boolean robotMovementObserved   = false;

    private MovementCallback pendingCallback;
    private Runnable         arrivalTimeoutRunnable;
    private Runnable         pendingConnectedCallback;
    private Runnable         pendingStartCallback;

    // ── Constructor ──────────────────────────────────────────────────────────

    public RobotMovementController() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)    // Infinite — we keep connection alive
                .writeTimeout(60, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)       // Keep-alive ping
                .build();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Move the robot to a named point of interest.
     *
     * @param locationName  The POI name exactly as configured in the robot's
     *                      Deployment Environment app (e.g. "waiting_area", "home")
     * @param callback      Lifecycle callbacks — all dispatched on the UI thread
     */
    public void moveToLocation(String locationName, MovementCallback callback) {
        Log.d(TAG, "=== moveToLocation: [" + locationName + "] ===");

        if (locationName == null || locationName.trim().isEmpty()) {
            if (callback != null) callback.onError("Destination name cannot be empty");
            return;
        }

        synchronized (this) {
            if (!movementCompleted) {
                if (callback != null)
                    callback.onError("Robot is already moving — wait for it to arrive first");
                return;
            }

            // Reset all state for this new movement
            currentMovementId    = "move_" + UUID.randomUUID().toString();
            pendingCallback      = callback;
            movementCompleted    = false;
            movementCommandSent  = false;
            movementCommandAccepted = false;
            robotMovementObserved   = false;
        }

        // Notify UI that we are starting
        if (callback != null) mainHandler.post(() -> callback.onStatusUpdate("Connecting to robot…"));

        // Capture the movement ID at this point — used for correlation in callbacks
        final String movementId = currentMovementId;

        // Open the WebSocket (or reuse existing connection)
        pendingConnectedCallback = () -> sendMovementCommand(movementId, locationName.trim());
        pendingStartCallback     = () -> {
            if (callback != null) callback.onMovementStart();
        };
        connectToRobot();
    }

    /**
     * Release all resources — call from Activity.onDestroy().
     */
    public synchronized void cleanup() {
        Log.d(TAG, "cleanup() called");
        movementCompleted = true;
        currentMovementId = null;
        pendingCallback   = null;
        pendingConnectedCallback = null;
        pendingStartCallback     = null;
        cancelArrivalTimeout();
        if (webSocket != null) {
            try { webSocket.close(1000, "Cleanup"); } catch (Exception ignored) {}
            webSocket = null;
        }
        isConnected = false;
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ── WebSocket connection ─────────────────────────────────────────────────

    private void connectToRobot() {
        if (isConnected && webSocket != null) {
            Log.d(TAG, "Already connected — proceeding immediately");
            Runnable cb = pendingConnectedCallback;
            pendingConnectedCallback = null;
            if (cb != null) mainHandler.post(cb);
            return;
        }

        String wsUrl = ConfigManager.getRobotWebSocketUrl();
        Log.d(TAG, "Connecting to robot at: " + wsUrl);

        try {
            Request request = new Request.Builder().url(wsUrl).build();
            webSocket = httpClient.newWebSocket(request, new RosbridgeListener());
        } catch (Exception e) {
            Log.e(TAG, "Error connecting: " + e.getMessage(), e);
            completeWithError("Cannot connect to robot: " + e.getMessage());
        }
    }

    // ── rosbridge message sending ────────────────────────────────────────────

    /**
     * After connection, subscribe to both status topics then send the navigation command.
     */
    private void onWebSocketOpened() {
        Log.d(TAG, "WebSocket opened — subscribing to status topics");
        if (!subscribeToTopic("/navi_status",  "actionlib_msgs/GoalStatus",    "sub_navi"))  return;
        subscribeToTopic("/robot_status", "yutong_assistance/RobotStatus", "sub_robot");

        // Now fire the pending movement command
        Runnable cb = pendingConnectedCallback;
        pendingConnectedCallback = null;
        if (cb != null) mainHandler.post(cb);
    }

    private boolean subscribeToTopic(String topic, String type, String id) {
        try {
            JSONObject sub = new JSONObject();
            sub.put("op",    "subscribe");
            sub.put("topic", topic);
            sub.put("type",  type);
            sub.put("id",    id);
            boolean sent = webSocket.send(sub.toString());
            Log.d(TAG, (sent ? "✓" : "✗") + " Subscribed to " + topic);
            return sent;
        } catch (Exception e) {
            Log.e(TAG, "Error subscribing to " + topic + ": " + e.getMessage());
            completeWithError("Could not subscribe to " + topic);
            return false;
        }
    }

    /**
     * Send the /poi_id service call — this is THE navigation command.
     *
     * Payload sent to rosbridge:
     * {
     *   "op": "call_service",
     *   "id": "<movementId>",
     *   "service": "/poi_id",
     *   "args": { "uuid": "<movementId>", "poi": "<locationName>" }
     * }
     */
    private void sendMovementCommand(String movementId, String locationName) {
        if (!movementId.equals(currentMovementId) || movementCompleted) return;

        if (!isConnected || webSocket == null) {
            completeWithError("WebSocket not connected");
            return;
        }

        try {
            JSONObject args = new JSONObject();
            args.put("uuid", movementId);
            args.put("poi",  locationName);    // ← Point name from Deployment Environment

            JSONObject command = new JSONObject();
            command.put("op",      "call_service");
            command.put("id",      movementId);
            command.put("service", "/poi_id");
            command.put("args",    args);

            // Register the arrival timeout BEFORE sending
            scheduleArrivalTimeout(movementId);

            String json = command.toString();
            Log.d(TAG, "Sending navigation command → poi=[" + locationName + "] id=" + movementId);
            Log.d(TAG, "Payload: " + json);

            if (!webSocket.send(json)) {
                cancelArrivalTimeout();
                completeWithError("Command could not be sent (WebSocket buffer full?)");
                return;
            }

            movementCommandSent = true;
            Log.d(TAG, "✓ Navigation command sent — waiting for acceptance and arrival");

            // Update UI
            MovementCallback cb = pendingCallback;
            if (cb != null) mainHandler.post(() -> cb.onStatusUpdate("Command sent → waiting for robot…"));

        } catch (Exception e) {
            Log.e(TAG, "Error building/sending command: " + e.getMessage(), e);
            completeWithError("Error sending command: " + e.getMessage());
        }
    }

    // ── Incoming message handling ────────────────────────────────────────────

    /**
     * Handles: service_response → confirms the robot accepted the destination
     */
    private void handleServiceResponse(JSONObject message) {
        if (!isActiveMovement()) return;

        String responseId = message.optString("id", "");
        if (!responseId.equals(currentMovementId)) return;   // Not for this movement

        boolean result   = message.optBoolean("result", false);
        JSONObject values = message.optJSONObject("values");
        boolean accepted  = values != null && values.optBoolean("success", false);

        Log.d(TAG, "service_response: result=" + result + ", accepted=" + accepted);

        if (result && accepted) {
            Log.d(TAG, "✓ Robot accepted the navigation destination");
            movementCommandAccepted = true;

            // Fire onMovementStart callback
            Runnable startCb = pendingStartCallback;
            pendingStartCallback = null;
            if (startCb != null) mainHandler.post(startCb);

            MovementCallback cb = pendingCallback;
            if (cb != null) mainHandler.post(() -> cb.onStatusUpdate("Robot is moving…"));
        } else {
            // Robot rejected the destination — usually means point name doesn't exist
            String available = "";
            if (values != null && values.optJSONArray("avaliable_list") != null) {
                available = " | Available points: " + values.optJSONArray("avaliable_list");
            }
            String info = message.optString("info", "unknown rejection");
            completeWithError("Robot rejected destination [" + info + "]" + available);
        }
    }

    /**
     * Handles: /navi_status publish → authoritative arrival signal
     */
    private void handleNaviStatus(JSONObject msg) {
        if (!isActiveMovement()) return;
        int status = readStatus(msg, "status");
        Log.d(TAG, "/navi_status: " + status);

        if (RobotNavigationStatus.isNavigationRunning(status)) {
            robotMovementObserved = true;
            MovementCallback cb = pendingCallback;
            if (cb != null) mainHandler.post(() -> cb.onStatusUpdate("Robot moving… (nav running)"));
        } else if (RobotNavigationStatus.isNavigationArrival(status)) {
            confirmArrival("/navi_status");
        } else if (RobotNavigationStatus.isNavigationCancelled(status)) {
            completeWithError("Navigation was cancelled by the robot");
        } else if (RobotNavigationStatus.isNavigationFailure(status)) {
            completeWithError("Navigation failed (navi_status=" + status + ")");
        }
    }

    /**
     * Handles: /robot_status publish → secondary arrival confirmation
     */
    private void handleRobotStatus(JSONObject msg) {
        if (!isActiveMovement()) return;
        int status = readStatus(msg, "nav_status");
        Log.d(TAG, "/robot_status nav_status: " + status);

        if (RobotNavigationStatus.isRobotRunning(status)) {
            robotMovementObserved = true;
        } else if (RobotNavigationStatus.isRobotArrival(status)) {
            confirmArrival("/robot_status");
        } else if (RobotNavigationStatus.isRobotCancelled(status)) {
            completeWithError("Navigation was cancelled (robot_status=" + status + ")");
        } else if (RobotNavigationStatus.isRobotFailure(status)) {
            completeWithError("Navigation failed (robot_status=" + status + ")");
        }
    }

    /**
     * Arrival guard — only fires once after:
     *   1. Command was accepted (movementCommandAccepted == true)
     *   2. Robot was observed moving (robotMovementObserved == true)
     */
    private void confirmArrival(String source) {
        if (!movementCommandAccepted) {
            Log.w(TAG, "Ignoring arrival before command acceptance from " + source);
            return;
        }
        if (!robotMovementObserved) {
            Log.w(TAG, "Ignoring uncorrelated arrival (no running transition seen) from " + source);
            return;
        }
        Log.d(TAG, "✓ Arrival confirmed by " + source);
        completeWithArrival();
    }

    // ── Completion ───────────────────────────────────────────────────────────

    private synchronized void completeWithArrival() {
        if (movementCompleted) return;

        movementCompleted = true;
        cancelArrivalTimeout();

        MovementCallback cb = pendingCallback;
        clearState();
        if (cb != null) mainHandler.post(cb::onMovementComplete);
    }

    private synchronized void completeWithError(String error) {
        if (movementCompleted) return;

        Log.e(TAG, "Movement failed: " + error);
        movementCompleted = true;
        cancelArrivalTimeout();

        MovementCallback cb = pendingCallback;
        clearState();
        if (cb != null) mainHandler.post(() -> cb.onError(error));
    }

    private void clearState() {
        pendingCallback          = null;
        pendingConnectedCallback = null;
        pendingStartCallback     = null;
        currentMovementId        = null;
        movementCommandSent      = false;
        movementCommandAccepted  = false;
        robotMovementObserved    = false;
    }

    // ── Arrival timeout ──────────────────────────────────────────────────────

    private void scheduleArrivalTimeout(String movementId) {
        cancelArrivalTimeout();
        arrivalTimeoutRunnable = () -> {
            if (!movementCompleted && movementId.equals(currentMovementId)) {
                String reason;
                if (!movementCommandAccepted) {
                    reason = "Robot did not accept destination before timeout";
                } else if (!robotMovementObserved) {
                    reason = "Robot accepted command but didn't start moving";
                } else {
                    reason = "Robot moved but did not report arrival in time";
                }
                completeWithError("Timeout: " + reason);
            }
        };
        mainHandler.postDelayed(arrivalTimeoutRunnable, ARRIVAL_TIMEOUT_MS);
    }

    private void cancelArrivalTimeout() {
        if (arrivalTimeoutRunnable != null) {
            mainHandler.removeCallbacks(arrivalTimeoutRunnable);
            arrivalTimeoutRunnable = null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isActiveMovement() {
        return !movementCompleted && currentMovementId != null && movementCommandSent;
    }

    private int readStatus(JSONObject msg, String key) {
        Object value = msg.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt(((String) value).trim()); }
            catch (NumberFormatException ignored) { return -1; }
        }
        return -1;
    }

    // ── WebSocket listener (inner class) ─────────────────────────────────────

    private class RosbridgeListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket ws, Response response) {
            if (RobotMovementController.this.webSocket != ws) {
                ws.close(1000, "Superseded");
                return;
            }
            Log.d(TAG, "✓ WebSocket connected");
            isConnected = true;
            onWebSocketOpened();
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            if (RobotMovementController.this.webSocket != ws) return;
            Log.v(TAG, "← Message: " + text);

            try {
                JSONObject msg = new JSONObject(text);
                String op = msg.optString("op", "");

                if ("service_response".equalsIgnoreCase(op)) {
                    handleServiceResponse(msg);
                } else if ("publish".equalsIgnoreCase(op)) {
                    String topic = msg.optString("topic", "");
                    JSONObject payload = msg.optJSONObject("msg");
                    if (payload == null) return;

                    if ("/navi_status".equals(topic)) {
                        handleNaviStatus(payload);
                    } else if ("/robot_status".equals(topic)) {
                        handleRobotStatus(payload);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing message: " + e.getMessage(), e);
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            if (RobotMovementController.this.webSocket != ws) return;
            Log.e(TAG, "✗ WebSocket failure: " + t.getMessage(), t);
            isConnected = false;
            completeWithError("Connection to robot failed: " + t.getMessage());
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            if (RobotMovementController.this.webSocket != ws) return;
            Log.d(TAG, "WebSocket closed (code=" + code + ")");
            isConnected = false;
            if (!movementCompleted) {
                completeWithError("Robot connection closed unexpectedly (code " + code + ")");
            }
        }
    }
}
