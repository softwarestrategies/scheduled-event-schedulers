package io.softwarestrategies.scheduledevent.service;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.softwarestrategies.scheduledevent.domain.DeliveryType;
import io.softwarestrategies.scheduledevent.domain.ScheduledEvent;
import io.softwarestrategies.scheduledevent.dto.KafkaEventMessage;
import io.softwarestrategies.scheduledevent.repository.ScheduledEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerServiceTest {

    @Mock
    private ScheduledEventRepository scheduledEventRepository;

    @Mock
    private KafkaProducerService kafkaProducerService;

    @Mock
    private Counter eventsPersistedCounter;

    @Mock
    private Timer databaseBatchTimer;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private KafkaConsumerService kafkaConsumerService;

    @Captor
    private ArgumentCaptor<ScheduledEvent> eventCaptor;

    private KafkaEventMessage createMessage(String id, String extJobId) {
        return KafkaEventMessage.builder()
                .messageId(id)
                .externalJobId(extJobId)
                .source("test-source")
                .scheduledAt(Instant.now().plus(1, ChronoUnit.HOURS))
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

        when(scheduledEventRepository.existsByUniqueKey(anyString(), anyString(), any())).thenReturn(false);
        when(scheduledEventRepository.insertIgnoreDuplicate(any())).thenReturn(1);

        // When
        kafkaConsumerService.consumeIngestionBatch(batch, acknowledgment);

        // Then
        verify(scheduledEventRepository, times(2)).insertIgnoreDuplicate(eventCaptor.capture());

        List<ScheduledEvent> savedEvents = eventCaptor.getAllValues();
        assertThat(savedEvents).hasSize(2);
        assertThat(savedEvents.get(0).getExternalJobId()).isEqualTo("job-1");
        assertThat(savedEvents.get(0).getId()).isNotNull(); // Verify UUID manual generation
        assertThat(savedEvents.get(1).getExternalJobId()).isEqualTo("job-2");
        assertThat(savedEvents.get(1).getId()).isNotNull();

        verify(eventsPersistedCounter, times(2)).increment();
        verify(acknowledgment).acknowledge();
        verifyNoInteractions(kafkaProducerService);
    }

    @Test
    void shouldDeduplicateUsingCaffeineCache() {
        // Given
        KafkaEventMessage duplicateMsg = createMessage("duplicate-id", "job-1");
        List<KafkaEventMessage> batch1 = Collections.singletonList(duplicateMsg);
        List<KafkaEventMessage> batch2 = Collections.singletonList(duplicateMsg); // Exact same message ID

        when(scheduledEventRepository.existsByUniqueKey(anyString(), anyString(), any())).thenReturn(false);
        when(scheduledEventRepository.insertIgnoreDuplicate(any())).thenReturn(1);

        // When processing first batch, it should persist
        kafkaConsumerService.consumeIngestionBatch(batch1, acknowledgment);

        // When processing second batch, Caffeine cache should catch it and skip DB
        // entirely
        kafkaConsumerService.consumeIngestionBatch(batch2, acknowledgment);

        // Then
        // Should only be inserted once
        verify(scheduledEventRepository, times(1)).insertIgnoreDuplicate(any());
        // existsByUniqueKey should only be called once (during first batch)
        verify(scheduledEventRepository, times(1)).existsByUniqueKey(anyString(), anyString(), any());
    }

    @Test
    void shouldHandleDatabaseNativeDuplicateWithoutErroring() {
        // Given
        KafkaEventMessage msg1 = createMessage("msg-1", "job-1");
        List<KafkaEventMessage> batch = Collections.singletonList(msg1);

        when(scheduledEventRepository.existsByUniqueKey(anyString(), anyString(), any())).thenReturn(false);
        // Simulate native ON CONFLICT DO NOTHING returning 0 rows affected
        when(scheduledEventRepository.insertIgnoreDuplicate(any())).thenReturn(0);

        // When
        kafkaConsumerService.consumeIngestionBatch(batch, acknowledgment);

        // Then
        verify(scheduledEventRepository, times(1)).insertIgnoreDuplicate(any());
        // Counter should NOT be incremented since it was a duplicate native skip
        verify(eventsPersistedCounter, never()).increment();
        // Acknowledgment should still happen
        verify(acknowledgment).acknowledge();
        // Should NOT go to DLQ
        verifyNoInteractions(kafkaProducerService);
    }

    @Test
    void shouldSendToDlqOnUnexpectedDatabaseError() {
        // Given
        KafkaEventMessage msg1 = createMessage("msg-1", "job-1");
        List<KafkaEventMessage> batch = Collections.singletonList(msg1);

        when(scheduledEventRepository.existsByUniqueKey(anyString(), anyString(), any())).thenReturn(false);
        when(scheduledEventRepository.insertIgnoreDuplicate(any()))
                .thenThrow(new RuntimeException("Database connection lost"));

        // When
        kafkaConsumerService.consumeIngestionBatch(batch, acknowledgment);

        // Then
        verify(scheduledEventRepository, times(1)).insertIgnoreDuplicate(any());
        verify(eventsPersistedCounter, never()).increment();

        // Original message should be sent to DLQ
        ArgumentCaptor<KafkaEventMessage> dlqCaptor = ArgumentCaptor.forClass(KafkaEventMessage.class);
        verify(kafkaProducerService).sendToDlq(dlqCaptor.capture(), anyString());
        assertThat(dlqCaptor.getValue().getExternalJobId()).isEqualTo("job-1");

        verify(acknowledgment).acknowledge(); // Batch itself is ack'd to prevent poison pill loops
    }
}
