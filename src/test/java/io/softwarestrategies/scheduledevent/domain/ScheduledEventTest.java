package io.softwarestrategies.scheduledevent.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledEventTest {

    @Test
    void testMarkFailed_WithRetriesAvailable_ShouldApplyExponentialBackoff() {
        ScheduledEvent event = new ScheduledEvent();
        event.setStatus(EventStatus.PROCESSING);
        event.setMaxRetries(3);
        event.setRetryCount(0); // Before failure
        
        Instant before = Instant.now();
        event.markFailed("Temporary failure");
        Instant after = Instant.now();

        assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        
        // Expected delay: 30 * 2^(1 - 1) = 30 seconds
        Instant expectedMin = before.plusSeconds(30);
        Instant expectedMax = after.plusSeconds(30);
        
        assertThat(event.getScheduledAt()).isBetween(expectedMin, expectedMax);
        assertThat(event.getPartitionKey()).isEqualTo(ScheduledEvent.calculatePartitionKey(event.getScheduledAt()));
    }
    
    @Test
    void testMarkFailed_WithNoRetriesAvailable_ShouldMarkDeadLetter() {
        ScheduledEvent event = new ScheduledEvent();
        event.setStatus(EventStatus.PROCESSING);
        event.setMaxRetries(3);
        event.setRetryCount(3); // Already maxed out
        
        event.markFailed("Final failure");

        assertThat(event.getStatus()).isEqualTo(EventStatus.DEAD_LETTER);
        assertThat(event.getRetryCount()).isEqualTo(4); // incremented during evaluation
    }
    
    @Test
    void testMarkFailed_MultipleRetries_ShouldDoubleBackoff() {
        ScheduledEvent event = new ScheduledEvent();
        event.setStatus(EventStatus.PROCESSING);
        event.setMaxRetries(5);
        
        // 1st failure (retryCount -> 1) -> 30s
        Instant before1 = Instant.now();
        event.markFailed("err1");
        Instant after1 = Instant.now();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getScheduledAt()).isBetween(before1.plusSeconds(30), after1.plusSeconds(30));
        
        // 2nd failure (retryCount -> 2) -> 60s
        Instant before2 = Instant.now();
        event.markFailed("err2");
        Instant after2 = Instant.now();
        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getScheduledAt()).isBetween(before2.plusSeconds(60), after2.plusSeconds(60));
        
        // 3rd failure (retryCount -> 3) -> 120s
        Instant before3 = Instant.now();
        event.markFailed("err3");
        Instant after3 = Instant.now();
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getScheduledAt()).isBetween(before3.plusSeconds(120), after3.plusSeconds(120));
        
        // 4th failure (retryCount -> 4) -> 240s
        Instant before4 = Instant.now();
        event.markFailed("err4");
        Instant after4 = Instant.now();
        assertThat(event.getRetryCount()).isEqualTo(4);
        assertThat(event.getScheduledAt()).isBetween(before4.plusSeconds(240), after4.plusSeconds(240));
        
        // 5th failure (retryCount -> 5) -> should be DEAD_LETTER
        event.markFailed("err5");
        assertThat(event.getStatus()).isEqualTo(EventStatus.DEAD_LETTER);
        assertThat(event.getRetryCount()).isEqualTo(5);
    }
}
