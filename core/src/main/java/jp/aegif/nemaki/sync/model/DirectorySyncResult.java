/*******************************************************************************
 * Copyright (c) 2013 aegif.
 *
 * This file is part of NemakiWare.
 *
 * NemakiWare is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NemakiWare is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with NemakiWare.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *     aegif - Directory Sync feature implementation
 ******************************************************************************/
package jp.aegif.nemaki.sync.model;

import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DirectorySyncResult {

    public enum SyncStatus {
        SUCCESS, PARTIAL, FAILED, IN_PROGRESS
    }

    private String syncId;
    private String repositoryId;
    private GregorianCalendar startTime;
    private GregorianCalendar endTime;
    private SyncStatus status;
    private boolean dryRun;

    private int groupsCreated;
    private int groupsUpdated;
    private int groupsDeleted;
    private int groupsSkipped;
    private int usersAdded;
    private int usersUpdated;
    private int usersRemoved;
    private int usersSkipped;

    private List<SyncError> errors = new ArrayList<>();
    private List<SyncWarning> warnings = new ArrayList<>();

    public DirectorySyncResult() {
        this.startTime = new GregorianCalendar();
        this.status = SyncStatus.IN_PROGRESS;
    }

    public DirectorySyncResult(String repositoryId, boolean dryRun) {
        this();
        this.repositoryId = repositoryId;
        this.dryRun = dryRun;
        this.syncId = "sync-" + UUID.randomUUID().toString();
    }

    public void complete(SyncStatus status) {
        this.endTime = new GregorianCalendar();
        this.status = status;
    }

    public void incrementGroupsCreated() {
        this.groupsCreated++;
    }

    public void incrementGroupsUpdated() {
        this.groupsUpdated++;
    }

    public void incrementGroupsDeleted() {
        this.groupsDeleted++;
    }

    public void incrementGroupsSkipped() {
        this.groupsSkipped++;
    }

    public void incrementUsersAdded() {
        this.usersAdded++;
    }

    public void incrementUsersUpdated() {
        this.usersUpdated++;
    }

    public void incrementUsersRemoved() {
        this.usersRemoved++;
    }

    public void incrementUsersSkipped() {
        this.usersSkipped++;
    }

    public void addError(String groupId, String message) {
        this.errors.add(new SyncError(groupId, message));
    }

    public void addWarning(String groupId, String message) {
        this.warnings.add(new SyncWarning(groupId, message));
    }

    public String getSyncId() {
        return syncId;
    }

    public void setSyncId(String syncId) {
        this.syncId = syncId;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public GregorianCalendar getStartTime() {
        return startTime;
    }

    public void setStartTime(GregorianCalendar startTime) {
        this.startTime = startTime;
    }

    public GregorianCalendar getEndTime() {
        return endTime;
    }

    public void setEndTime(GregorianCalendar endTime) {
        this.endTime = endTime;
    }

    public SyncStatus getStatus() {
        return status;
    }

    public void setStatus(SyncStatus status) {
        this.status = status;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getGroupsCreated() {
        return groupsCreated;
    }

    public void setGroupsCreated(int groupsCreated) {
        this.groupsCreated = groupsCreated;
    }

    public int getGroupsUpdated() {
        return groupsUpdated;
    }

    public void setGroupsUpdated(int groupsUpdated) {
        this.groupsUpdated = groupsUpdated;
    }

    public int getGroupsDeleted() {
        return groupsDeleted;
    }

    public void setGroupsDeleted(int groupsDeleted) {
        this.groupsDeleted = groupsDeleted;
    }

    public int getGroupsSkipped() {
        return groupsSkipped;
    }

    public void setGroupsSkipped(int groupsSkipped) {
        this.groupsSkipped = groupsSkipped;
    }

    public int getUsersAdded() {
        return usersAdded;
    }

    public void setUsersAdded(int usersAdded) {
        this.usersAdded = usersAdded;
    }

    public int getUsersUpdated() {
        return usersUpdated;
    }

    public void setUsersUpdated(int usersUpdated) {
        this.usersUpdated = usersUpdated;
    }

    public int getUsersRemoved() {
        return usersRemoved;
    }

    public void setUsersRemoved(int usersRemoved) {
        this.usersRemoved = usersRemoved;
    }

    public int getUsersSkipped() {
        return usersSkipped;
    }

    public void setUsersSkipped(int usersSkipped) {
        this.usersSkipped = usersSkipped;
    }

    public List<SyncError> getErrors() {
        return errors;
    }

    public void setErrors(List<SyncError> errors) {
        this.errors = errors;
    }

    public List<SyncWarning> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<SyncWarning> warnings) {
        this.warnings = warnings;
    }

    public static class SyncError {
        private String groupId;
        private String message;

        public SyncError() {}

        public SyncError(String groupId, String message) {
            this.groupId = groupId;
            this.message = message;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class SyncWarning {
        private String groupId;
        private String message;

        public SyncWarning() {}

        public SyncWarning(String groupId, String message) {
            this.groupId = groupId;
            this.message = message;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
