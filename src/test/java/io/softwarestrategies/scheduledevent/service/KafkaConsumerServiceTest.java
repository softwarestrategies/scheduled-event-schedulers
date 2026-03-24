package io.softwarestrategies.scheduledevent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.softwarestrategies.scheduledevent.domain.DeliveryType;
import io.softwarestrategies.scheduledevent.dto.KafkaEventMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerServiceTest {

    @Mock
    private KafkaProducerService kafkaProducerService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Counter eventsPersistedCounter;

    @Mock
    private Timer databaseBatchTimer;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private KafkaConsumerService kafkaConsumerService;

    private KafkaEventMessage createMessage(String id, String extJobId) {
        return KafkaEventMessage.builder()
                .messageId(id)
                .externalJobId(extJobId)
                .source("test-source")
                .scheduledAt(Instant.parse("2026-01-01T10:00:00Z").plus(1, ChronoUnit.HOURS))
                .deliveryType(DeliveryType.HTTP)
                .destination("http://localhost/test")
                .payload("{\"key\":\"value\"}")
                .maxRetries(3)
                .build();
    }

    @Test
    void shouldProcessBatchAndInsertSuccessfully() {
        // Given
        KafkaEventMessage msg1 = createMessage("msg-1", "job-1");
        KafkaEventMessage msg2 = createMessage("msg-2", "job-2");
        List<KafkaEventMessage> batch = Arrays.asList(msg1, msg2);

        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1, 1});

        // When
        kafkaConsumerService.consumeIngestionBatch(batch, acknowledgment);

        // Then
        verify(jdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        verify(eventsPersistedCounter, times(2)).increment();
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(kafkaProducerService);
    }

    @Test
    void shouldDeduplicateUsingCaffeineCache() {
        // Given
        KafkaEventMessage duplicateMsg = createMessage("duplicate-id", "job-1");
        List<KafkaEventMessage> batch1 = Collections.singletonList(duplicateMsg);
        List<KafkaEventMessage> batch2 = Collections.singletonList(duplicateMsg); // Same message ID

        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});

        // When
        kafkaConsumerService.consumeIngestionBatch(batch1, acknowledgment); // persists, populates cache
        kafkaConsumerService.consumeIngestionBatch(batch2, acknowledgment); // cache hit, skips entirely

        // Then: bulk insert called only once — second batch was deduplicated by cache
        verify(jdbcTemplate, times(1)).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        verify(eventsPersistedCounter, times(1)).increment();
        verifyNoInteractions(kafkaProducerService);
    }

    @Test
    void shouldHandleDatabaseNativeDuplicateWithoutErroring() {
        // Given
        KafkaEventMessage msg1 = createMessage("msg-1", "job-1");
        List<KafkaEventMessage> batch = Collections.singletonList(msg1);

        // ON CONFLICT DO NOTHING returns 0 rows affected
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{0});

        // When
        kafkaConsumerService.consumeIngestionBatch(batch, acknowledgment);

        // Then
        verify(jdbcTemplate).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
        // Counter must NOT be incremented — row was a database-level duplicate
        verify(eventsPersistedCounter, never()).increment();
        // Batch must still be acknowledged
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(kafkaProducerService);
    }

    @Test
    void shouldSendAllMessagesToDlqOnBatchDatabaseError() {
        // Given
        KafkaEventMessage msg1 = createMessage("msg-1", "job-1");
        KafkaEventMessage msg2 = createMessage("msg-2", "job-2");
        List<KafkaEventMessage> batch = Arrays.asList(msg1, msg2);

        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenThrow(new RuntimeException("Database connection lost"));

        when(kafkaProducerService.sendToDlq(any(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // When
        kafkaConsumerService.consumeIngestionBatch(batch, acknowledgment);

        // Then
        verify(eventsPersistedCounter, never()).increment();

        // All messages in the failed batch must be sent to DLQ
        ArgumentCaptor<KafkaEventMessage> dlqCaptor = ArgumentCaptor.forClass(KafkaEventMessage.class);
        verify(kafkaProducerService, times(2)).sendToDlq(dlqCaptor.capture(), eq("Persist failed: Database connection lost"));
        assertThat(dlqCaptor.getAllValues().stream().map(KafkaEventMessage::getExternalJobId))
                .containsExactlyInAnyOrder("job-1", "job-2");

        // Batch is still acknowledged to prevent poison-pill redelivery loops
        verify(acknowledgment).acknowledge();
    }
}
