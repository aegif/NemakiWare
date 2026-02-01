package jp.aegif.nemaki.sync.scheduler;

import jp.aegif.nemaki.sync.service.CloudDirectorySyncService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.support.CronExpression;

import java.util.concurrent.*;

/**
 * Spring-based scheduler for cloud directory synchronization.
 * Runs delta sync on a cron schedule.
 * Follows the same pattern as DirectorySyncScheduler.
 */
public class CloudDirectorySyncScheduler {

	private static final Log log = LogFactory.getLog(CloudDirectorySyncScheduler.class);

	private CloudDirectorySyncService cloudDirectorySyncService;
	private PropertyManager propertyManager;

	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> scheduledTask;
	private volatile boolean initialized = false;
	private final Object initLock = new Object();

	public void init() {
		if (initialized) {
			return;
		}

		synchronized (initLock) {
			if (initialized) {
				return;
			}

			String enabled = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED);
			if (!"true".equalsIgnoreCase(enabled)) {
				log.info("Cloud directory sync is disabled");
				initialized = true;
				return;
			}

			String cronExpression = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON);
			if (cronExpression == null || cronExpression.trim().isEmpty()) {
				log.info("Cloud directory sync cron expression is not configured, scheduling disabled");
				initialized = true;
				return;
			}

			if (!CronExpression.isValidExpression(cronExpression)) {
				log.error("Invalid cloud directory sync cron expression: " + cronExpression);
				initialized = true;
				return;
			}

			scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "CloudDirectorySyncScheduler");
				t.setDaemon(true);
				return t;
			});

			scheduleNextExecution(cronExpression);
			initialized = true;
			log.info("Cloud directory sync scheduler initialized with cron: " + cronExpression);
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

		log.debug("Next cloud directory sync scheduled for: " + next);
	}

	private void executeSync() {
		log.info("Starting scheduled cloud directory delta sync");

		String providers = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS);
		if (providers == null || providers.trim().isEmpty()) {
			log.debug("No cloud directory sync providers configured");
			return;
		}

		// For each configured provider, run delta sync on default repository
		// TODO: support multi-repository via repositoryInfoMap if needed
		String repositoryId = "bedroom";

		for (String provider : providers.split(",")) {
			provider = provider.trim();
			if (provider.isEmpty()) continue;

			try {
				log.info("Executing scheduled delta sync for provider: " + provider);
				cloudDirectorySyncService.startDeltaSync(repositoryId, provider);
			} catch (Exception e) {
				log.error("Scheduled cloud directory sync failed for provider " + provider + ": " + e.getMessage(), e);
			}
		}
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
		log.info("Cloud directory sync scheduler stopped");
	}

	public boolean isSchedulerActive() {
		return scheduler != null && !scheduler.isShutdown();
	}

	public void setCloudDirectorySyncService(CloudDirectorySyncService cloudDirectorySyncService) {
		this.cloudDirectorySyncService = cloudDirectorySyncService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
