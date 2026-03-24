package io.softwarestrategies.scheduledevent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.softwarestrategies.scheduledevent.domain.EventStatus;
import io.softwarestrategies.scheduledevent.domain.ScheduledEvent;
import io.softwarestrategies.scheduledevent.dto.KafkaEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Kafka consumer service that handles the second phase of ingestion:
 * Kafka → Database.
 *
 * Processes messages in batches for optimal throughput.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

	private final KafkaProducerService kafkaProducerService;
	private final JdbcTemplate jdbcTemplate;
	private final Counter eventsPersistedCounter;
	private final Timer databaseBatchTimer;

	private static final String BULK_INSERT_SQL = """
			INSERT INTO scheduled_events (
			    id, external_job_id, source, scheduled_at, delivery_type,
			    destination, payload, status, retry_count, max_retries,
			    created_at, updated_at, partition_key
			) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
			ON CONFLICT (external_job_id, source, scheduled_at, partition_key) DO NOTHING
			""";

	/**
	 * Concurrent LRU Cache for message deduplication
	 */
	private final Cache<String, Boolean> recentMessageIds = Caffeine.newBuilder()
			.maximumSize(100_000)
			.build();

	/**
	 * Batch consumer for ingestion topic.
	 * Processes messages in a single bulk INSERT for high throughput.
	 * ON CONFLICT DO NOTHING handles duplicates natively at the database level.
	 */
	@KafkaListener(topics = "${app.kafka.topics.ingestion}", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "kafkaListenerContainerFactory")
	@Transactional
	public void consumeIngestionBatch(List<KafkaEventMessage> messages, Acknowledgment ack) {
		if (messages == null || messages.isEmpty()) {
			ack.acknowledge();
			return;
		}

		log.debug("Received batch of {} messages from ingestion topic", messages.size());

		Timer.Sample sample = Timer.start();
		List<ScheduledEvent> eventsToSave = new ArrayList<>();
		List<KafkaEventMessage> messagesToSave = new ArrayList<>();
		List<KafkaEventMessage> failedMessages = new ArrayList<>();
		List<CompletableFuture<Void>> dlqFutures = new ArrayList<>();

		for (KafkaEventMessage message : messages) {
			try {
				if (isDuplicate(message)) {
					log.debug("Skipping duplicate message: {}", message.getMessageId());
					continue;
				}

				ScheduledEvent event = convertToEntity(message);
				eventsToSave.add(event);
				messagesToSave.add(message);
				trackMessageId(message.getMessageId());

			} catch (Exception e) {
				log.error("Failed to process message: {}", message.getMessageId(), e);
				failedMessages.add(message);
			}
		}

		if (!eventsToSave.isEmpty()) {
			try {
				int[] results = jdbcTemplate.batchUpdate(BULK_INSERT_SQL, new BatchPreparedStatementSetter() {
					@Override
					public void setValues(PreparedStatement ps, int i) throws SQLException {
						ScheduledEvent event = eventsToSave.get(i);
						ps.setObject(1,  event.getId());
						ps.setString(2,  event.getExternalJobId());
						ps.setString(3,  event.getSource());
						ps.setTimestamp(4,  Timestamp.from(event.getScheduledAt()));
						ps.setString(5,  event.getDeliveryType().name());
						ps.setString(6,  event.getDestination());
						ps.setString(7,  event.getPayload());
						ps.setString(8,  event.getStatus().name());
						ps.setInt(9,     event.getRetryCount());
						ps.setInt(10,    event.getMaxRetries());
						ps.setTimestamp(11, Timestamp.from(event.getCreatedAt()));
						ps.setTimestamp(12, Timestamp.from(event.getUpdatedAt()));
						ps.setInt(13,    event.getPartitionKey());
					}

					@Override
					public int getBatchSize() {
						return eventsToSave.size();
					}
				});

				for (int count : results) {
					if (count > 0) {
						eventsPersistedCounter.increment();
					} else {
						log.debug("Duplicate event skipped (native constraint)");
					}
				}

			} catch (Exception e) {
				log.error("Failed to persist batch of {} events", eventsToSave.size(), e);
				for (KafkaEventMessage message : messagesToSave) {
					dlqFutures.add(kafkaProducerService.sendToDlq(message, "Persist failed: " + e.getMessage()));
				}
			}
		}

		for (KafkaEventMessage failed : failedMessages) {
			dlqFutures.add(kafkaProducerService.sendToDlq(failed, "Message processing failed"));
		}

		// Wait for all DLQ sends to succeed
		try {
			if (!dlqFutures.isEmpty()) {
				CompletableFuture.allOf(dlqFutures.toArray(new CompletableFuture[0])).join();
			}
		} catch (Exception e) {
			log.error("Failed to send one or more messages to DLQ. Aborting batch acknowledgment to prevent data loss.",
					e);
			throw new RuntimeException("DLQ send failed, aborting batch", e);
		}

		sample.stop(databaseBatchTimer);
		ack.acknowledge();
	}

	/**
	 * Check if this message has been processed recently (in-memory deduplication).
	 * Database-level duplicates are handled by ON CONFLICT DO NOTHING in the bulk insert.
	 */
	private boolean isDuplicate(KafkaEventMessage message) {
		return recentMessageIds.getIfPresent(message.getMessageId()) != null;
	}

	/**
	 * Track message ID for deduplication.
	 */
	private void trackMessageId(String messageId) {
		recentMessageIds.put(messageId, Boolean.TRUE);
	}

	/**
	 * Convert Kafka message to entity.
	 */
	private ScheduledEvent convertToEntity(KafkaEventMessage message) {
		ScheduledEvent event = ScheduledEvent.builder()
				.externalJobId(message.getExternalJobId())
				.source(message.getSource())
				.scheduledAt(message.getScheduledAt())
				.deliveryType(message.getDeliveryType())
				.destination(message.getDestination())
				.payload(message.getPayload())
				.status(EventStatus.PENDING)
				.retryCount(0)
				.maxRetries(message.getMaxRetries() != null ? message.getMaxRetries() : 3)
				.createdAt(Instant.now())
				.updatedAt(Instant.now())
				.partitionKey(ScheduledEvent.calculatePartitionKey(message.getScheduledAt()))
				.build();

		// ID must be generated manually before native query insert
		if (event.getId() == null) {
			event.setId(UUID.randomUUID());
		}

		return event;
	}
}
