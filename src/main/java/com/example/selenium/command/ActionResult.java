package com.example.selenium.command;

import org.json.JSONObject;

/**
 * Outcome of executing a single action (an input or a click) within a step.
 *
 * <p>Failures here are expected, recoverable results - not exceptional control
 * flow. The navigation loop collects these and feeds any failure observations
 * back to the model through the action history, so the model can adapt instead
 * of repeating an action that cannot succeed.
 */
public class ActionResult {

    private final boolean success;
    private final String action;       // the attempted action JSON, e.g. {"action":"click","id":"..."}
    private final String observation;  // failure reason, or "" on success

    private ActionResult(boolean success, String action, String observation) {
        this.success = success;
        this.action = action;
        this.observation = observation;
    }

    public static ActionResult success(JSONObject action) {
        return new ActionResult(true, action.toString(), "");
    }

    public static ActionResult failure(JSONObject action, String observation) {
        return new ActionResult(false, action.toString(), observation);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getAction() {
        return action;
    }

    public String getObservation() {
        return observation;
    }

    @Override
    public String toString() {
        return success ? "OK " + action : "FAILED " + action + " -> " + observation;
    }
}
