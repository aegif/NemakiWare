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
package jp.aegif.nemaki.sync.scheduler;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.support.CronExpression;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.sync.model.DirectorySyncConfig;
import jp.aegif.nemaki.sync.model.DirectorySyncResult;
import jp.aegif.nemaki.sync.service.DirectorySyncService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

/**
 * Spring-based scheduler for directory synchronization.
 * 
 * Supports IP-based node control for cluster environments:
 * - If directory.sync.schedule.node.ip is configured, only the node with matching IP will execute sync
 * - If not configured, all nodes will execute sync (suitable for single-node deployments)
 * 
 * Uses cron expression from directory.sync.schedule.cron property.
 */
public class DirectorySyncScheduler {

    private static final Log log = LogFactory.getLog(DirectorySyncScheduler.class);

    private DirectorySyncService directorySyncService;
    private PropertyManager propertyManager;
    private RepositoryInfoMap repositoryInfoMap;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;
    private volatile boolean initialized = false;
    private final Object initLock = new Object();

    private Set<String> localIpAddresses;

    public void init() {
        if (initialized) {
            return;
        }

        synchronized (initLock) {
            if (initialized) {
                return;
            }

            localIpAddresses = collectLocalIpAddresses();
            log.info("Local IP addresses: " + localIpAddresses);

            boolean scheduleEnabled = propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_SCHEDULE_ENABLED);
            if (!scheduleEnabled) {
                log.info("Directory sync scheduling is disabled");
                initialized = true;
                return;
            }

            String cronExpression = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_SCHEDULE_CRON);
            if (cronExpression == null || cronExpression.trim().isEmpty()) {
                log.warn("Directory sync cron expression is not configured");
                initialized = true;
                return;
            }

            if (!CronExpression.isValidExpression(cronExpression)) {
                log.error("Invalid cron expression: " + cronExpression);
                initialized = true;
                return;
            }

            String configuredNodeIp = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_SCHEDULE_NODE_IP);
            if (configuredNodeIp != null && !configuredNodeIp.trim().isEmpty()) {
                if (!isLocalNode(configuredNodeIp.trim())) {
                    log.info("Directory sync scheduling skipped - configured node IP (" + configuredNodeIp + 
                            ") does not match this node's IPs: " + localIpAddresses);
                    initialized = true;
                    return;
                }
                log.info("This node matches configured IP (" + configuredNodeIp + "), will execute scheduled syncs");
            } else {
                log.info("No node IP configured, this node will execute scheduled syncs");
            }

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DirectorySyncScheduler");
                t.setDaemon(true);
                return t;
            });

            scheduleNextExecution(cronExpression);
            initialized = true;
            log.info("Directory sync scheduler initialized with cron: " + cronExpression);
        }
    }

    private void scheduleNextExecution(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime next = cron.next(now);
        
        if (next == null) {
            log.warn("Could not determine next execution time for cron: " + cronExpression);
            return;
        }

        long delayMillis = java.time.Duration.between(now, next).toMillis();
        
        scheduledTask = scheduler.schedule(() -> {
            try {
                executeSync();
            } finally {
                scheduleNextExecution(cronExpression);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);

        log.debug("Next directory sync scheduled for: " + next);
    }

    private void executeSync() {
        log.info("Starting scheduled directory sync");

        try {
            Set<String> repositoryIds = repositoryInfoMap.keys();
            
            for (String repositoryId : repositoryIds) {
                try {
                    DirectorySyncConfig config = directorySyncService.getConfig(repositoryId);
                    
                    if (!config.isEnabled()) {
                        log.debug("Directory sync disabled for repository: " + repositoryId);
                        continue;
                    }

                    if (!config.isScheduleEnabled()) {
                        log.debug("Scheduled sync disabled for repository: " + repositoryId);
                        continue;
                    }

                    log.info("Executing scheduled sync for repository: " + repositoryId);
                    DirectorySyncResult result = directorySyncService.syncGroups(repositoryId, false);
                    
                    log.info("Scheduled sync completed for repository " + repositoryId + 
                            ": status=" + result.getStatus() +
                            ", users added=" + result.getUsersAdded() +
                            ", users updated=" + result.getUsersUpdated() +
                            ", users removed=" + result.getUsersRemoved() +
                            ", groups created=" + result.getGroupsCreated() +
                            ", groups updated=" + result.getGroupsUpdated() +
                            ", groups deleted=" + result.getGroupsDeleted());

                } catch (Exception e) {
                    log.error("Scheduled sync failed for repository " + repositoryId + ": " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error during scheduled directory sync: " + e.getMessage(), e);
        }
    }

    private boolean isLocalNode(String configuredIp) {
        return localIpAddresses.contains(configuredIp);
    }

    private Set<String> collectLocalIpAddresses() {
        Set<String> addresses = new HashSet<>();
        
        addresses.add("127.0.0.1");
        addresses.add("localhost");

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress addr = inetAddresses.nextElement();
                    addresses.add(addr.getHostAddress());
                    addresses.add(addr.getHostName());
                }
            }
        } catch (SocketException e) {
            log.warn("Failed to enumerate network interfaces: " + e.getMessage());
        }

        try {
            InetAddress localHost = InetAddress.getLocalHost();
            addresses.add(localHost.getHostAddress());
            addresses.add(localHost.getHostName());
        } catch (Exception e) {
            log.debug("Failed to get local host: " + e.getMessage());
        }

        return addresses;
    }

    public void destroy() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Directory sync scheduler stopped");
    }

    public boolean isSchedulerActive() {
        return scheduler != null && !scheduler.isShutdown();
    }

    public void setDirectorySyncService(DirectorySyncService directorySyncService) {
        this.directorySyncService = directorySyncService;
    }

    public void setPropertyManager(PropertyManager propertyManager) {
        this.propertyManager = propertyManager;
    }

    public void setRepositoryInfoMap(RepositoryInfoMap repositoryInfoMap) {
        this.repositoryInfoMap = repositoryInfoMap;
    }
}
