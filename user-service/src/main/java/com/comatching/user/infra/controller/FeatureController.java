package com.comatching.user.infra.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.comatching.common.annotation.CurrentMember;
import com.comatching.common.annotation.RequireRole;
import com.comatching.common.domain.enums.MemberRole;
import com.comatching.common.dto.member.MemberInfo;
import com.comatching.common.dto.response.ApiResponse;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureState;
import com.comatching.user.domain.operations.FeatureOperations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class FeatureController {
	private final FeatureOperations operations;
	// Whether this subscriber has received an initial snapshot.
	private final Map<SseEmitter, Boolean> streams = new ConcurrentHashMap<>();
	private List<FeatureState> lastSnapshot = List.of();
	private long lastBroadcast = System.nanoTime();

	public record Change(@NotNull Boolean open, @NotNull @Size(max = 240) String message,
		@NotNull @PositiveOrZero Long version) {}

	@ExceptionHandler({DataAccessException.class, TransactionException.class})
	public ResponseEntity<ApiResponse<Void>> unavailable() {
		var error = GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE;
		return ResponseEntity.status(error.getHttpStatus()).body(ApiResponse.errorResponse(error));
	}

	@ExceptionHandler(AsyncRequestNotUsableException.class)
	public void disconnected() {
		// A disconnected event stream already has a committed response.
	}

	@GetMapping("/api/public/operations")
	public ResponseEntity<List<FeatureState>> snapshot() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.snapshot());
	}

	@GetMapping("/api/public/operations/{feature}")
	public ResponseEntity<FeatureState> state(@PathVariable Feature feature) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.state(feature));
	}

	@GetMapping(value = "/api/public/operations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public synchronized ResponseEntity<SseEmitter> stream() throws IOException {
		var emitter = new SseEmitter(300_000L);
		emitter.onCompletion(() -> streams.remove(emitter));
		emitter.onTimeout(() -> {
			streams.remove(emitter);
			emitter.complete();
		});
		emitter.onError(e -> streams.remove(emitter));
		// Keep the streaming HTTP request free of JPA access so it never holds a DB connection while idle.
		emitter.send(SseEmitter.event().comment("connected"));
		streams.put(emitter, false);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").body(emitter);
	}

	// Each instance broadcasts changes committed by any administrator, including on another instance.
	@Scheduled(fixedDelay = 1000)
	public synchronized void publishChanges() {
		if (streams.isEmpty()) return;
		List<FeatureState> current = operations.snapshot();
		boolean changed = !current.equals(lastSnapshot);
		long now = System.nanoTime();
		boolean heartbeat = now - lastBroadcast >= TimeUnit.SECONDS.toNanos(15);
		if (changed || heartbeat) lastBroadcast = now;
		lastSnapshot = current;
		for (var entry : streams.entrySet()) {
			SseEmitter emitter = entry.getKey();
			try {
				if (changed || !entry.getValue()) {
					emitter.send(SseEmitter.event().name("features").data(current));
					streams.replace(emitter, true);
				} else if (heartbeat) {
					emitter.send(SseEmitter.event().comment("keep-alive"));
				}
			} catch (IOException | IllegalStateException disconnected) {
				// The servlet container completes failed sends. Completing again can interrupt delivery to other tabs.
				streams.remove(emitter);
			}
		}
	}

	@GetMapping("/api/v1/admin/operations")
	@RequireRole(MemberRole.ROLE_ADMIN)
	public List<FeatureOperations.AdminState> admin(@CurrentMember MemberInfo actor) {
		return operations.adminSnapshot();
	}

	@PatchMapping("/api/v1/admin/operations/{feature}")
	@RequireRole(MemberRole.ROLE_ADMIN)
	public FeatureOperations.AdminState change(@CurrentMember MemberInfo actor, @PathVariable Feature feature,
		@Valid @RequestBody Change input) {
		return operations.change(feature, input.open(), input.message(), input.version(), actor.memberId());
	}
}
