package io.softwarestrategies.scheduledevent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.softwarestrategies.scheduledevent.domain.ScheduledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Service responsible for delivering scheduled events to their destinations.
 * Supports both HTTP POST and Kafka topic delivery.
 * Uses RestClient with virtual threads for efficient blocking I/O.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventDeliveryService {

	private final RestClient restClient;
	private final KafkaProducerService kafkaProducerService;
	private final CircuitBreakerRegistry circuitBreakerRegistry;
	private final Counter httpDeliveryCounter;
	private final Counter kafkaDeliveryCounter;
	private final Timer eventDeliveryTimer;

	private static final Set<Integer> RETRIABLE_STATUS_CODES = Set.of(408, 429, 500, 502, 503, 504);

	/**
	 * Deliver an event to its destination.
	 */
	public DeliveryResult deliverEvent(ScheduledEvent event) {
		Timer.Sample sample = Timer.start();

		try {
			return switch (event.getDeliveryType()) {
				case HTTP -> deliverViaHttp(event);
				case KAFKA -> deliverViaKafka(event);
				default -> throw new IllegalArgumentException("Unknown delivery type: " + event.getDeliveryType());
			};
		} finally {
			sample.stop(eventDeliveryTimer);
		}
	}

	/**
	 * Deliver event via HTTP POST with retry logic.
	 */
	private DeliveryResult deliverViaHttp(ScheduledEvent event) {
		httpDeliveryCounter.increment();

		try {
			// Extract domain for CircuitBreaker scoping
			String domain = extractDomain(event.getDestination());
			CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(domain);

			// Wrap the HTTP request in the CircuitBreaker
			ResponseEntity<Void> response = circuitBreaker.executeSupplier(() -> restClient.post()
					.uri(event.getDestination())
					.contentType(MediaType.APPLICATION_JSON)
					.body(event.getPayload())
					.retrieve()
					.toBodilessEntity());

			log.debug("HTTP delivery successful. EventId: {}, Status: {}",
					event.getId(), response.getStatusCode());
			return DeliveryResult.ofSuccess();

		} catch (CallNotPermittedException ex) {
			// Circuit Breaker is OPEN. Fast-fail without attempting HTTP.
			String error = "Circuit Breaker OPEN for domain. Delivery aborted.";
			log.warn("HTTP delivery fast-failed (Circuit Breaker). EventId: {}, Error: {}",
					event.getId(), error);
			// It is retriable later when circuit closes
			return DeliveryResult.ofFailure(error, true);

		} catch (RestClientResponseException ex) {
			String error = String.format("HTTP %d: %s", ex.getStatusCode().value(), ex.getStatusText());
			boolean retriable = isRetriableStatusCode(ex.getStatusCode().value());
			log.warn("HTTP delivery failed. EventId: {}, Retriable: {}, Error: {}",
					event.getId(), retriable, error);
			return DeliveryResult.ofFailure(error, retriable);

		} catch (Exception ex) {
			boolean retriable = isRetriableException(ex);
			log.warn("HTTP delivery failed. EventId: {}, Retriable: {}, Error: {}",
					event.getId(), retriable, ex.getMessage());
			return DeliveryResult.ofFailure(ex.getMessage(), retriable);
		}
	}

	/**
	 * Deliver event via Kafka topic.
	 */
	private DeliveryResult deliverViaKafka(ScheduledEvent event) {
		kafkaDeliveryCounter.increment();

		try {
			kafkaProducerService.sendToExternalTopic(
					event.getDestination(),
					event.getExternalJobId(),
					event.getPayload()).join(); // Block on virtual thread

			log.debug("Kafka delivery successful. EventId: {}, Topic: {}",
					event.getId(), event.getDestination());
			return DeliveryResult.ofSuccess();

		} catch (Exception ex) {
			String error = "Kafka delivery failed: " + ex.getMessage();
			log.warn("Kafka delivery failed. EventId: {}, Error: {}", event.getId(), error);
			return DeliveryResult.ofFailure(error, true);
		}
	}

	private boolean isRetriableStatusCode(int statusCode) {
		return RETRIABLE_STATUS_CODES.contains(statusCode);
	}

	private boolean isRetriableException(Exception ex) {
		Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
		return cause instanceof ConnectException || cause instanceof SocketTimeoutException;
	}

	private boolean isRetriable(Exception ex) {
		if (ex instanceof RestClientResponseException rcEx) {
			return isRetriableStatusCode(rcEx.getStatusCode().value());
		}
		return isRetriableException(ex);
	}

	private String extractErrorMessage(Exception ex) {
		if (ex instanceof RestClientResponseException rcEx) {
			return String.format("HTTP %d: %s", rcEx.getStatusCode().value(), rcEx.getStatusText());
		}
		return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Extracts the host domain from a URL to use as the CircuitBreaker name.
	 */
	private String extractDomain(String url) {
		try {
			URI uri = new URI(url);
			String host = uri.getHost();
			return host != null ? host : "unknown-domain";
		} catch (URISyntaxException e) {
			return "invalid-url";
		}
	}

	/**
	 * Result of a delivery attempt.
	 */
	public record DeliveryResult(boolean success, String error, boolean retriable) {
		public static DeliveryResult ofSuccess() {
			return new DeliveryResult(true, null, false);
		}

		public static DeliveryResult ofFailure(String error, boolean retriable) {
			return new DeliveryResult(false, error, retriable);
		}
	}
}