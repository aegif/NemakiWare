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
 * Dynamically reads cron configuration from CouchDB every 60 seconds,
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
	private ScheduledFuture<?> syncTask;       // next sync execution
	private volatile String activeCron;        // currently scheduled cron (null = not scheduled)

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

		// Initial schedule
		reconcileSchedule();

		// Always poll for config changes every 60 seconds
		scheduler.scheduleWithFixedDelay(
				this::reconcileSchedule,
				CONFIG_CHECK_INTERVAL_SECONDS,
				CONFIG_CHECK_INTERVAL_SECONDS,
				TimeUnit.SECONDS);

		logger.info("Purview sync scheduler initialized (activeCron={})", activeCron);
	}

	/**
	 * Reads current CouchDB config and starts/changes/stops the sync schedule.
	 * Called every 60 seconds by the config check timer, and at init.
	 * Only modifies the sync task when the cron expression actually changes.
	 */
	void reconcileSchedule() {
		if (scheduler == null || scheduler.isShutdown()) {
			return;
		}

		String currentCron = resolveEffectiveCron();

		if (Objects.equals(currentCron, activeCron)) {
			return; // No change
		}

		// Cron changed — cancel pending sync task
		cancelSyncTask();

		if (currentCron == null) {
			logger.info("Purview sync schedule stopped (was: {})", activeCron);
			activeCron = null;
			return;
		}

		logger.info("Purview sync schedule updated: {} -> {}", activeCron, currentCron);
		activeCron = currentCron;
		scheduleNextSync(currentCron);
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

	private void scheduleNextSync(String cronExpression) {
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
			syncTask = scheduler.schedule(() -> {
				try {
					executeSync();
				} finally {
					// After execution, re-read config and schedule next
					String effectiveCron = resolveEffectiveCron();
					if (effectiveCron != null) {
						activeCron = effectiveCron;
						scheduleNextSync(effectiveCron);
					} else {
						activeCron = null;
						logger.info("Purview sync cron cleared after execution");
					}
				}
			}, delayMillis, TimeUnit.MILLISECONDS);
			logger.debug("Next Purview incremental sync scheduled for: {} (delay: {}ms)", next, delayMillis);
		} catch (RejectedExecutionException e) {
			logger.debug("Purview sync schedule rejected (scheduler shutting down)");
		}
	}

	private void cancelSyncTask() {
		if (syncTask != null) {
			syncTask.cancel(false);
			syncTask = null;
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
