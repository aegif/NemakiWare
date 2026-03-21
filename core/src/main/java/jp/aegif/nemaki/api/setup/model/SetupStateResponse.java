package jp.aegif.nemaki.api.setup.model;

import java.util.List;

public class SetupStateResponse {
    private boolean setupRequired;
    private String state;
    private String serverVersion;
    private List<String> completedSteps;

    public boolean isSetupRequired() { return setupRequired; }
    public void setSetupRequired(boolean setupRequired) { this.setupRequired = setupRequired; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getServerVersion() { return serverVersion; }
    public void setServerVersion(String serverVersion) { this.serverVersion = serverVersion; }
    public List<String> getCompletedSteps() { return completedSteps; }
    public void setCompletedSteps(List<String> completedSteps) { this.completedSteps = completedSteps; }
}
