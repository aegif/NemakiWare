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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
 * Cron-based scheduler for directory synchronization (LDAP/AD).
 * Dynamically reads cron configuration every 60 seconds,
 * so changes made via the admin UI or CouchDB take effect without restart.
 *
 * <p>Supports IP-based node control for cluster environments:
 * the {@code directory.sync.schedule.node.ip} property is re-evaluated on
 * every 60-second config poll, so changing the designated node at runtime
 * takes effect without restart.</p>
 *
 * <p>Uses a generation token to prevent double-scheduling on cron changes.</p>
 */
public class DirectorySyncScheduler {

    private static final Log log = LogFactory.getLog(DirectorySyncScheduler.class);
    private static final long CONFIG_CHECK_INTERVAL_SECONDS = 60;

    private DirectorySyncService directorySyncService;
    private PropertyManager propertyManager;
    private RepositoryInfoMap repositoryInfoMap;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> syncTask;
    private volatile String activeCron;
    private final AtomicLong generation = new AtomicLong(0);

    private Set<String> localIpAddresses;

    public void init() {
        localIpAddresses = collectLocalIpAddresses();
        log.info("Local IP addresses: " + localIpAddresses);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DirectorySyncScheduler");
            t.setDaemon(true);
            return t;
        });

        reconcileSchedule();

        scheduler.scheduleWithFixedDelay(
                this::reconcileSchedule,
                CONFIG_CHECK_INTERVAL_SECONDS,
                CONFIG_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        log.info("Directory sync scheduler initialized (activeCron=" + activeCron + ")");
    }

    void reconcileSchedule() {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }

        String currentCron = resolveEffectiveCron();

        if (Objects.equals(currentCron, activeCron)) {
            return;
        }

        cancelSyncTask();
        long gen = generation.incrementAndGet();

        if (currentCron == null) {
            if (activeCron != null) {
                log.info("Directory sync schedule stopped (was: " + activeCron + ")");
            }
            activeCron = null;
            return;
        }

        log.info("Directory sync schedule updated: " + activeCron + " -> " + currentCron);
        activeCron = currentCron;
        scheduleNextSync(currentCron, gen);
    }

    /**
     * Resolves the effective cron expression considering enabled state,
     * cron validity, and cluster node IP routing.
     * Called on every 60-second config poll so all checks are dynamic.
     */
    private String resolveEffectiveCron() {
        boolean scheduleEnabled = propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_SCHEDULE_ENABLED);
        if (!scheduleEnabled) {
            return null;
        }

        // Cluster node routing: re-evaluate on every poll
        String configuredNodeIp = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_SCHEDULE_NODE_IP);
        if (configuredNodeIp != null && !configuredNodeIp.trim().isEmpty()) {
            if (!isLocalNode(configuredNodeIp.trim())) {
                return null;
            }
        }

        String cron = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_SCHEDULE_CRON);
        if (cron == null || cron.isBlank()) {
            return null;
        }
        cron = cron.trim();
        if (!CronExpression.isValidExpression(cron)) {
            log.warn("Invalid directory sync cron expression: " + cron);
            return null;
        }
        return cron;
    }

    private void scheduleNextSync(String cronExpression, long gen) {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = cron.next(now);

        if (next == null) {
            log.warn("Could not determine next execution time for cron: " + cronExpression);
            return;
        }

        long delayMillis = Duration.between(now, next).toMillis();

        try {
            syncTask = scheduler.schedule(() -> {
                try {
                    executeSync();
                } finally {
                    if (generation.get() != gen) {
                        log.debug("Directory sync generation changed, not re-arming");
                        return;
                    }
                    String effectiveCron = resolveEffectiveCron();
                    if (effectiveCron != null) {
                        activeCron = effectiveCron;
                        scheduleNextSync(effectiveCron, gen);
                    } else {
                        activeCron = null;
                        log.info("Directory sync cron cleared after execution");
                    }
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
            log.debug("Next directory sync scheduled for: " + next + " (delay: " + delayMillis + "ms)");
        } catch (RejectedExecutionException e) {
            log.debug("Directory sync schedule rejected (scheduler shutting down)");
        }
    }

    private void cancelSyncTask() {
        if (syncTask != null) {
            syncTask.cancel(false);
            syncTask = null;
        }
    }

    private void executeSync() {
        // Dynamic check: re-evaluate enabled + node IP before executing
        if (!propertyManager.readBoolean(PropertyKey.DIRECTORY_SYNC_SCHEDULE_ENABLED)) {
            log.debug("Directory sync skipped: disabled (dynamic check)");
            return;
        }

        String configuredNodeIp = propertyManager.readValue(PropertyKey.DIRECTORY_SYNC_SCHEDULE_NODE_IP);
        if (configuredNodeIp != null && !configuredNodeIp.trim().isEmpty()) {
            if (!isLocalNode(configuredNodeIp.trim())) {
                log.info("Directory sync skipped: node IP mismatch (dynamic check)");
                return;
            }
        }

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
        cancelSyncTask();
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

    String getActiveCron() {
        return activeCron;
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
