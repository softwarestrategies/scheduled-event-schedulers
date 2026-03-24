package io.softwarestrategies.scheduledevent.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.softwarestrategies.scheduledevent.domain.DeliveryType;
import io.softwarestrategies.scheduledevent.domain.ScheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventDeliveryServiceTest {

    @Mock
    private RestClient restClient;

    @Mock
    private KafkaProducerService kafkaProducerService;

    @Mock
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Mock
    private Counter httpDeliveryCounter;

    @Mock
    private Counter kafkaDeliveryCounter;

    @Mock
    private Timer eventDeliveryTimer;

    @Mock
    private Timer.Sample timerSample;

    @InjectMocks
    private EventDeliveryService eventDeliveryService;

    private ScheduledEvent createHttpEvent(String destination) {
        ScheduledEvent event = new ScheduledEvent();
        event.setId(java.util.UUID.randomUUID());
        event.setDeliveryType(DeliveryType.HTTP);
        event.setDestination(destination);
        event.setPayload("{\"test\":\"data\"}");
        return event;
    }

    @BeforeEach
    void setUp() {
        try (var mockedTimer = mockStatic(Timer.class)) {
            mockedTimer.when(Timer::start).thenReturn(timerSample);
        }
    }

    @Test
    void shouldExtractDomainAndCallCircuitBreaker() {
        // Given
        ScheduledEvent event = createHttpEvent("https://api.noisy-neighbor.com/webhook");

        // Use a real CircuitBreaker to avoid deep mocking issues with Resilience4j
        CircuitBreaker realCb = spy(CircuitBreaker.ofDefaults("api.noisy-neighbor.com"));
        when(circuitBreakerRegistry.circuitBreaker("api.noisy-neighbor.com")).thenReturn(realCb);

        doAnswer(invocation -> {
            Supplier<ResponseEntity<Void>> supplier = invocation.getArgument(0);
            return ResponseEntity.ok().build();
        }).when(realCb).executeSupplier(any());

        // When
        EventDeliveryService.DeliveryResult result = eventDeliveryService.deliverEvent(event);

        // Then
        assertThat(result.success()).isTrue();
        verify(circuitBreakerRegistry).circuitBreaker("api.noisy-neighbor.com");
    }

    @Test
    void shouldFastFailWhenCircuitBreakerIsOpen() {
        // Given
        ScheduledEvent event = createHttpEvent("https://api.down-neighbor.com/webhook");

        // Use a real CircuitBreaker to avoid deep mocking issues with Resilience4j
        // exceptions
        CircuitBreaker realCb = spy(CircuitBreaker.ofDefaults("api.down-neighbor.com"));
        when(circuitBreakerRegistry.circuitBreaker("api.down-neighbor.com")).thenReturn(realCb);

        // Manually force the CircuitBreaker open so it throws CallNotPermittedException
        // naturally
        realCb.transitionToOpenState();

        // When
        EventDeliveryService.DeliveryResult result = eventDeliveryService.deliverEvent(event);

        // Then
        assertThat(result.success()).isFalse();
        assertThat(result.retriable()).isTrue();
        assertThat(result.error()).contains("Circuit Breaker OPEN for domain");

        verifyNoInteractions(restClient);
    }
}
