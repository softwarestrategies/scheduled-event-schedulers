package io.softwarestrategies.scheduledevent.service;

import io.softwarestrategies.scheduledevent.repository.ScheduledEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Service for cleaning up old executed events.
 * Runs daily to remove completed events beyond the retention period.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class EventCleanupService {

	private final ScheduledEventRepository scheduledEventRepository;
	private final PlatformTransactionManager transactionManager;

	@Value("${app.cleanup.retention-days:7}")
	private int retentionDays;

	@Value("${app.cleanup.batch-size:10000}")
	private int batchSize;

	/**
	 * Daily cleanup job that removes old completed events.
	 * Runs at 2 AM by default.
	 */
	@Scheduled(cron = "${app.cleanup.cron:0 0 2 * * *}")
	public void cleanupOldEvents() {
		log.info("Starting scheduled cleanup of old events. Retention: {} days", retentionDays);

		long totalDeleted = 0;
		int iterations = 0;
		int maxIterations = 1000; // Safety limit

		Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

		try {
			int deleted;
			do {
				deleted = deleteEventsBatch(cutoff);
				totalDeleted += deleted;
				iterations++;

				if (deleted > 0) {
					log.debug("Deleted batch of {} events. Total so far: {}", deleted, totalDeleted);
				}

				// Small delay to reduce database load
				if (deleted == batchSize) {
					Thread.sleep(100);
				}

			} while (deleted == batchSize && iterations < maxIterations);

			log.info("Cleanup completed. Total deleted: {} events in {} iterations",
					totalDeleted, iterations);

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Cleanup interrupted after deleting {} events", totalDeleted);
		} catch (Exception e) {
			log.error("Error during cleanup after deleting {} events", totalDeleted, e);
		}
	}

	/**
	 * Delete a batch of old events in its own transaction.
	 * Uses TransactionTemplate directly to ensure each batch commits independently,
	 * even when called via self-invocation (which bypasses Spring's AOP proxy).
	 */
	public int deleteEventsBatch(Instant cutoff) {
		Integer deleted = new TransactionTemplate(transactionManager)
				.execute(status -> scheduledEventRepository.deleteCompletedEventsBefore(cutoff, batchSize));
		return deleted != null ? deleted : 0;
	}

	/**
	 * Manual cleanup trigger for administrative purposes.
	 */
	public CleanupResult manualCleanup(int days) {
		log.info("Manual cleanup triggered for events older than {} days", days);

		Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
		long totalDeleted = 0;

		int deleted;
		do {
			deleted = deleteEventsBatch(cutoff);
			totalDeleted += deleted;
		} while (deleted == batchSize);

		log.info("Manual cleanup completed. Deleted {} events", totalDeleted);
		return new CleanupResult(totalDeleted, cutoff);
	}

	/**
	 * Get cleanup statistics.
	 */
	public CleanupStats getCleanupStats() {
		Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
		// This would require a count query - simplified here
		return new CleanupStats(retentionDays, batchSize, cutoff);
	}

	public record CleanupResult(long deletedCount, Instant cutoffTime) {}

	public record CleanupStats(int retentionDays, int batchSize, Instant nextCutoff) {}
}
