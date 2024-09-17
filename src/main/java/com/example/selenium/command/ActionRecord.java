package com.example.selenium.command;

public class ActionRecord {

    private final int step;
    private final String action;
    private final String observation;
    private final String coveragePercentage;

    public ActionRecord(int step, String action, String observation, String coveragePercentage) {
        this.step = step;
        this.action = action;
        this.observation = observation;
        this.coveragePercentage = coveragePercentage;
    }

    public ActionRecord(int step, String action, String explanation){
        this.step = step;
        this.action = action;
        this.observation = "";
        this.coveragePercentage = "";
    }

    public int getStep() {
        return step;
    }

    public String getAction() {
        return action;
    }

    public String getObservation() {
        return observation;
    }

    public String getCoveragePercentage() {
        return coveragePercentage;
    }

    public String toString(){
        return String.format("Step: %s,Action: <script>%s</script>, Explanation: %s", step, action, observation);
    }
}