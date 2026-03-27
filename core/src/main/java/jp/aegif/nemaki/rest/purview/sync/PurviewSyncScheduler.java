package jp.aegif.nemaki.rest.purview.sync;

import jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap;
import jp.aegif.nemaki.rest.purview.PurviewConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Cron-based scheduler for Purview/Atlas incremental synchronization.
 * Dynamically reads cron configuration from CouchDB on each reschedule,
 * so changes made via the admin UI take effect without restart.
 */
@Component
public class PurviewSyncScheduler {

	private static final Logger logger = LoggerFactory.getLogger(PurviewSyncScheduler.class);
	private static final long CONFIG_CHECK_INTERVAL_SECONDS = 60;

	private final PurviewConfig purviewConfig;
	private final PurviewIncrementalSyncService purviewIncrementalSyncService;
	private final RepositoryInfoMap repositoryInfoMap;

	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> scheduledTask;
	private volatile String activeCron;  // currently scheduled cron (null = not scheduled)

	@Autowired
	public PurviewSyncScheduler(
			PurviewConfig purviewConfig,
			PurviewIncrementalSyncService purviewIncrementalSyncService,
			RepositoryInfoMap repositoryInfoMap) {
		this.purviewConfig = purviewConfig;
		this.purviewIncrementalSyncService = purviewIncrementalSyncService;
		this.repositoryInfoMap = repositoryInfoMap;
	}

	@PostConstruct
	public void init() {
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "PurviewSyncScheduler");
			t.setDaemon(true);
			return t;
		});
		reconcileSchedule();
		logger.info("Purview sync scheduler initialized (activeCron={})", activeCron);
	}

	/**
	 * Reads current CouchDB config and starts/changes/stops the schedule accordingly.
	 * Called from init() and after each sync execution.
	 */
	void reconcileSchedule() {
		if (scheduler == null || scheduler.isShutdown()) {
			return;
		}

		String currentCron = resolveEffectiveCron();

		if (Objects.equals(currentCron, activeCron)) {
			// No change — schedule next with same cron, or poll if null
			if (currentCron != null) {
				scheduleNext(currentCron);
			} else {
				scheduleConfigCheck();
			}
			return;
		}

		// Cron changed — cancel pending task
		cancelPendingTask();

		if (currentCron == null) {
			logger.info("Purview sync schedule stopped (was: {})", activeCron);
			activeCron = null;
			scheduleConfigCheck();
			return;
		}

		logger.info("Purview sync schedule updated: {} -> {}", activeCron, currentCron);
		activeCron = currentCron;
		scheduleNext(currentCron);
	}

	private String resolveEffectiveCron() {
		if (!purviewConfig.isEnabled()) {
			return null;
		}
		String cron = purviewConfig.getSyncCron();
		if (cron == null || cron.isBlank()) {
			return null;
		}
		cron = cron.trim();
		if (!CronExpression.isValidExpression(cron)) {
			logger.warn("Invalid Purview sync cron expression: {}", cron);
			return null;
		}
		return cron;
	}

	private void scheduleNext(String cronExpression) {
		if (scheduler == null || scheduler.isShutdown()) {
			return;
		}
		CronExpression cron = CronExpression.parse(cronExpression);
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime next = cron.next(now);

		if (next == null) {
			logger.warn("Could not determine next execution time for cron: {}", cronExpression);
			return;
		}

		long delayMillis = Duration.between(now, next).toMillis();

		try {
			scheduledTask = scheduler.schedule(() -> {
				try {
					executeSync();
				} finally {
					reconcileSchedule();
				}
			}, delayMillis, TimeUnit.MILLISECONDS);
			logger.debug("Next Purview incremental sync scheduled for: {}", next);
		} catch (RejectedExecutionException e) {
			logger.debug("Purview sync schedule rejected (scheduler shutting down)");
		}
	}

	/**
	 * When no cron is configured, periodically re-check so that a cron
	 * added via the admin UI is picked up without restart.
	 */
	private void scheduleConfigCheck() {
		if (scheduler == null || scheduler.isShutdown()) {
			return;
		}
		try {
			scheduledTask = scheduler.schedule(
					this::reconcileSchedule,
					CONFIG_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
		} catch (RejectedExecutionException e) {
			// shutting down
		}
	}

	private void cancelPendingTask() {
		if (scheduledTask != null) {
			scheduledTask.cancel(false);
			scheduledTask = null;
		}
	}

	private void executeSync() {
		if (!purviewConfig.isEnabled()) {
			logger.debug("Purview sync skipped: purview.enabled is false (dynamic check)");
			return;
		}

		logger.info("Starting scheduled Purview incremental sync");

		List<String> repositoryIds = repositoryInfoMap.getMainRepositoryKeys();
		if (repositoryIds.isEmpty()) {
			logger.warn("No main repositories found, skipping Purview sync");
			return;
		}

		for (String repoId : repositoryIds) {
			try {
				logger.info("Executing scheduled Purview incremental sync for repository: {}", repoId);
				purviewIncrementalSyncService.startIncrementalSync(repoId, "scheduled");
			} catch (Exception e) {
				logger.error("Scheduled Purview incremental sync failed for repository {}: {}",
						repoId, e.getMessage(), e);
			}
		}
	}

	@PreDestroy
	public void destroy() {
		cancelPendingTask();
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
		logger.info("Purview sync scheduler stopped");
	}

	public boolean isSchedulerActive() {
		return scheduler != null && !scheduler.isShutdown();
	}

	/** Visible for testing. */
	String getActiveCron() {
		return activeCron;
	}
}
