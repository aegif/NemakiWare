package jp.aegif.nemaki.sync.scheduler;

import jp.aegif.nemaki.sync.service.CloudDirectorySyncService;
import jp.aegif.nemaki.util.PropertyManager;
import jp.aegif.nemaki.util.constant.PropertyKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cron-based scheduler for cloud directory synchronization.
 * Dynamically reads cron configuration every 60 seconds,
 * so changes made via the admin UI or CouchDB take effect without restart.
 *
 * <p>Uses a generation token to prevent double-scheduling on cron changes.</p>
 */
public class CloudDirectorySyncScheduler {

	private static final Log log = LogFactory.getLog(CloudDirectorySyncScheduler.class);
	private static final long CONFIG_CHECK_INTERVAL_SECONDS = 60;

	private CloudDirectorySyncService cloudDirectorySyncService;
	private PropertyManager propertyManager;

	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> syncTask;
	private volatile String activeCron;
	private final AtomicLong generation = new AtomicLong(0);

	public void init() {
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "CloudDirectorySyncScheduler");
			t.setDaemon(true);
			return t;
		});

		reconcileSchedule();

		scheduler.scheduleWithFixedDelay(
				this::reconcileSchedule,
				CONFIG_CHECK_INTERVAL_SECONDS,
				CONFIG_CHECK_INTERVAL_SECONDS,
				TimeUnit.SECONDS);

		log.info("Cloud directory sync scheduler initialized (activeCron=" + activeCron + ")");
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
			log.info("Cloud directory sync schedule stopped (was: " + activeCron + ")");
			activeCron = null;
			return;
		}

		log.info("Cloud directory sync schedule updated: " + activeCron + " -> " + currentCron);
		activeCron = currentCron;
		scheduleNextSync(currentCron, gen);
	}

	private String resolveEffectiveCron() {
		String enabled = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED);
		if (!"true".equalsIgnoreCase(enabled)) {
			return null;
		}
		String cron = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_CRON);
		if (cron == null || cron.isBlank()) {
			return null;
		}
		cron = cron.trim();
		if (!CronExpression.isValidExpression(cron)) {
			log.warn("Invalid cloud directory sync cron expression: " + cron);
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
						log.debug("Cloud directory sync generation changed, not re-arming");
						return;
					}
					String effectiveCron = resolveEffectiveCron();
					if (effectiveCron != null) {
						activeCron = effectiveCron;
						scheduleNextSync(effectiveCron, gen);
					} else {
						activeCron = null;
						log.info("Cloud directory sync cron cleared after execution");
					}
				}
			}, delayMillis, TimeUnit.MILLISECONDS);
			log.debug("Next cloud directory sync scheduled for: " + next + " (delay: " + delayMillis + "ms)");
		} catch (RejectedExecutionException e) {
			log.debug("Cloud directory sync schedule rejected (scheduler shutting down)");
		}
	}

	private void cancelSyncTask() {
		if (syncTask != null) {
			syncTask.cancel(false);
			syncTask = null;
		}
	}

	private void executeSync() {
		String enabled = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_ENABLED);
		if (!"true".equalsIgnoreCase(enabled)) {
			log.debug("Cloud directory sync skipped: disabled (dynamic check)");
			return;
		}

		log.info("Starting scheduled cloud directory delta sync");

		String providers = propertyManager.readValue(PropertyKey.CLOUD_DIRECTORY_SYNC_PROVIDERS);
		if (providers == null || providers.trim().isEmpty()) {
			log.debug("No cloud directory sync providers configured");
			return;
		}

		String repositoryId = null;
		try {
			jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap repoInfoMap =
				jp.aegif.nemaki.util.spring.SpringContext.getApplicationContext()
					.getBean("repositoryInfoMap", jp.aegif.nemaki.cmis.factory.info.RepositoryInfoMap.class);
			if (repoInfoMap != null) {
				repositoryId = repoInfoMap.getDefaultRepositoryId();
			}
		} catch (Exception e) {
			log.warn("Failed to get default repository ID from RepositoryInfoMap");
		}
		if (repositoryId == null) {
			log.warn("Default repository ID not available, skipping cloud directory sync");
			return;
		}

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
		log.info("Cloud directory sync scheduler stopped");
	}

	public boolean isSchedulerActive() {
		return scheduler != null && !scheduler.isShutdown();
	}

	String getActiveCron() {
		return activeCron;
	}

	public void setCloudDirectorySyncService(CloudDirectorySyncService cloudDirectorySyncService) {
		this.cloudDirectorySyncService = cloudDirectorySyncService;
	}

	public void setPropertyManager(PropertyManager propertyManager) {
		this.propertyManager = propertyManager;
	}
}
