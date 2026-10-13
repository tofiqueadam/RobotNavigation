package com.robot.navlearner;

/**
 * Vendor protocol 4.7.6 — navigation status codes.
 *
 * The robot publishes status on two separate WebSocket topics:
 *   /navi_status  → actionlib_msgs/GoalStatus (values 0–4)
 *   /robot_status → yutong_assistance/RobotStatus  (nav_status field, values 600–625)
 *
 * These two domains are COMPLETELY SEPARATE — do NOT cross-check them.
 * /navi_status is the authoritative arrival signal.
 *
 * Protocol reference: "Host computer communication protocol 4.7.6 (EN).docx"
 */
public final class RobotNavigationStatus {
    private RobotNavigationStatus() {}

    // ── /navi_status topic ───────────────────────────────────────────────────

    public static boolean isNavigationWaiting(int status)  { return status == 0; }
    public static boolean isNavigationRunning(int status)  { return status == 1; }
    public static boolean isNavigationCancelled(int status){ return status == 2; }
    /** Arrival confirmed — this is the primary "robot has arrived" signal */
    public static boolean isNavigationArrival(int status)  { return status == 3; }
    public static boolean isNavigationFailure(int status)  { return status == 4; }

    // ── /robot_status topic (nav_status field) ───────────────────────────────

    public static boolean isRobotWaiting(int status)   { return status == 600; }
    public static boolean isRobotRunning(int status)   { return status == 601; }
    public static boolean isRobotCancelled(int status) { return status == 602 || status == 606; }
    /** Secondary arrival confirmation */
    public static boolean isRobotArrival(int status)   { return status == 603; }

    public static boolean isRobotFailure(int status) {
        switch (status) {
            case 604: // No valid path
            case 605: // Navigation denied
            case 609: // Navigation status lost
            case 610: // Recharging running
            case 611: // Emergency stop active
            case 612: // Multipoint navigation running
            case 613: // Access-control navigation running
            case 614: // Cross-floor navigation running
            case 615: // Gate navigation running
            case 623: // No valid path found
            case 624: // Speed planning failed
            case 625: // Movement timeout
                return true;
            default:
                return false;
        }
    }
}
