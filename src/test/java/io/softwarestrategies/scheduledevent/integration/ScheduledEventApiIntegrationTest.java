package io.softwarestrategies.scheduledevent.integration;

import io.softwarestrategies.scheduledevent.domain.DeliveryType;
import io.softwarestrategies.scheduledevent.domain.EventStatus;
import io.softwarestrategies.scheduledevent.dto.*;
import io.softwarestrategies.scheduledevent.repository.ScheduledEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the REST API endpoints.
 * Uses RestClient for synchronous HTTP testing with virtual threads.
 */
@SuppressWarnings("rawtypes")
class ScheduledEventApiIntegrationTest extends BaseIntegrationTest {

	@Autowired
	private ScheduledEventRepository repository;

	private static final String BASE_URL = "/api/v1/events";

	@BeforeEach
	void setUp() {
		repository.deleteAll();
	}

	@Test
	@DisplayName("Should submit a single event successfully")
	void submitSingleEvent() {
		// Given
		ScheduledEventRequest request = createTestRequest();

		// When/Then
		ResponseEntity<ApiResponse> response = restClient.post()
				.uri(BASE_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.toEntity(ApiResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody().isSuccess()).isTrue();

		// Wait for event to be persisted via Kafka
		await().atMost(10, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.untilAsserted(() ->
						assertThat(repository.findByExternalJobId(request.getExternalJobId())).isPresent());
	}

	@Test
	@DisplayName("Should submit a batch of events successfully")
	void submitBatchEvents() {
		// Given
		BatchScheduledEventRequest batchRequest = BatchScheduledEventRequest.builder()
				.events(List.of(createTestRequest(), createTestRequest(), createTestRequest()))
				.build();

		// When/Then
		ResponseEntity<ApiResponse> response = restClient.post()
				.uri(BASE_URL + "/batch")
				.contentType(MediaType.APPLICATION_JSON)
				.body(batchRequest)
				.retrieve()
				.toEntity(ApiResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody().isSuccess()).isTrue();

		// Wait for events to be persisted
		await().atMost(10, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.untilAsserted(() -> assertThat(repository.count()).isGreaterThanOrEqualTo(3));
	}

	@Test
	@DisplayName("Should reject event with past scheduled time")
	void rejectPastScheduledTime() {
		// Given
		ScheduledEventRequest request = ScheduledEventRequest.builder()
				.externalJobId(UUID.randomUUID().toString())
				.source("test-source")
				.scheduledAt(Instant.now().minus(1, ChronoUnit.HOURS))
				.deliveryType(DeliveryType.HTTP)
				.destination("https://example.com/webhook")
				.payload(Map.of("key", "value"))
				.build();

		// When/Then
		ResponseEntity<Void> response = restClient.post()
				.uri(BASE_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.toBodilessEntity();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("Should get event by ID after persistence")
	void getEventById() {
		// Given
		ScheduledEventRequest request = createTestRequest();

		restClient.post()
				.uri(BASE_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.toBodilessEntity();

		// Wait for persistence
		await().atMost(10, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.until(() -> repository.findByExternalJobId(request.getExternalJobId()).isPresent());

		var event = repository.findByExternalJobId(request.getExternalJobId()).get();

		// When/Then
		ResponseEntity<ApiResponse> response = restClient.get()
				.uri(BASE_URL + "/" + event.getId())
				.retrieve()
				.toEntity(ApiResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().isSuccess()).isTrue();
	}

	@Test
	@DisplayName("Should get event by external job ID")
	void getEventByExternalJobId() {
		// Given
		ScheduledEventRequest request = createTestRequest();

		restClient.post()
				.uri(BASE_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.toBodilessEntity();

		// Wait for persistence
		await().atMost(10, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.until(() -> repository.findByExternalJobId(request.getExternalJobId()).isPresent());

		// When/Then
		ResponseEntity<ApiResponse> response = restClient.get()
				.uri(BASE_URL + "/external/" + request.getExternalJobId())
				.retrieve()
				.toEntity(ApiResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().isSuccess()).isTrue();
	}

	@Test
	@DisplayName("Should cancel pending event")
	void cancelEvent() {
		// Given
		ScheduledEventRequest request = createTestRequest();

		restClient.post()
				.uri(BASE_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.toBodilessEntity();

		// Wait for persistence
		await().atMost(10, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.until(() -> repository.findByExternalJobId(request.getExternalJobId()).isPresent());

		// When
		ResponseEntity<Void> response = restClient.delete()
				.uri(BASE_URL + "/external/" + request.getExternalJobId())
				.retrieve()
				.toBodilessEntity();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Then
		await().atMost(5, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.untilAsserted(() -> {
					var event = repository.findByExternalJobId(request.getExternalJobId()).get();
					assertThat(event.getStatus()).isEqualTo(EventStatus.CANCELLED);
				});
	}

	@Test
	@DisplayName("Should return 404 for non-existent event")
	void getNonExistentEvent() {
		ResponseEntity<Void> response = restClient.get()
				.uri(BASE_URL + "/" + UUID.randomUUID())
				.retrieve()
				.toBodilessEntity();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("Should get statistics")
	void getStatistics() {
		// Given - submit some events first
		for (int i = 0; i < 5; i++) {
			restClient.post()
					.uri(BASE_URL)
					.contentType(MediaType.APPLICATION_JSON)
					.body(createTestRequest())
					.retrieve()
					.toBodilessEntity();
		}

		// Wait for persistence
		await().atMost(10, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.until(() -> repository.count() >= 5);

		// When/Then
		ResponseEntity<ApiResponse> response = restClient.get()
				.uri(BASE_URL + "/statistics")
				.retrieve()
				.toEntity(ApiResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().isSuccess()).isTrue();
	}

	@Test
	@DisplayName("Should validate required fields")
	void validateRequiredFields() {
		// Given - request missing required fields
		ScheduledEventRequest request = ScheduledEventRequest.builder()
				.source("test-source")
				// Missing externalJobId, scheduledAt, deliveryType, destination, payload
				.build();

		// When/Then
		ResponseEntity<Void> response = restClient.post()
				.uri(BASE_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.toBodilessEntity();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("Should handle health check")
	void healthCheck() {
		ResponseEntity<Void> response = restClient.get()
				.uri(BASE_URL + "/health")
				.retrieve()
				.toBodilessEntity();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("Admin cleanup should return 401 without credentials")
	void adminCleanup_withoutAuth_returns401() {
		ResponseEntity<Void> response = restClient.post()
				.uri(BASE_URL + "/admin/cleanup")
				.retrieve()
				.toBodilessEntity();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@DisplayName("Admin cleanup should return 200 with valid credentials")
	void adminCleanup_withAuth_returns200() {
		ResponseEntity<ApiResponse> response = adminRestClient().post()
				.uri(BASE_URL + "/admin/cleanup")
				.retrieve()
				.toEntity(ApiResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().isSuccess()).isTrue();
	}

	private ScheduledEventRequest createTestRequest() {
		return ScheduledEventRequest.builder()
				.externalJobId(UUID.randomUUID().toString())
				.source("test-source")
				.scheduledAt(Instant.now().plus(1, ChronoUnit.HOURS))
				.deliveryType(DeliveryType.HTTP)
				.destination("https://example.com/webhook")
				.payload(Map.of("message", "test", "timestamp", Instant.now().toString()))
				.maxRetries(3)
				.build();
	}
}
