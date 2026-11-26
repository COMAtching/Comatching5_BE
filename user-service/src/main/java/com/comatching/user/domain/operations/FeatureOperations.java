package com.comatching.user.domain.operations;

import java.time.Instant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureGate;
import com.comatching.common.operations.FeatureState;

@Service
public class FeatureOperations implements FeatureGate, ApplicationRunner {
	public record AdminState(Feature feature, boolean open, String message, long version, Instant changedAt, Long changedBy) {
		static AdminState from(FeatureOperation operation) {
			return new AdminState(operation.getFeature(), operation.isOpenForUse(), operation.getMessage(),
				operation.getVersion(), operation.getChangedAt(), operation.getChangedBy());
		}
	}

	private final FeatureOperationRepository repository;
	private final MemberSessionShutdown shutdown;
	private final EntityManager entityManager;
	private final EnumSet<Feature> initiallyClosed;

	public FeatureOperations(FeatureOperationRepository repository, MemberSessionShutdown shutdown,
		EntityManager entityManager, @Value("${operations.initially-closed:}") String initial) {
		this.repository = repository;
		this.shutdown = shutdown;
		this.entityManager = entityManager;
		initiallyClosed = EnumSet.noneOf(Feature.class);
		Arrays.stream(initial.split(",")).map(String::strip).filter(s -> !s.isEmpty())
			.map(Feature::valueOf).forEach(initiallyClosed::add);
	}

	@Override
	public void run(ApplicationArguments ignored) {
		for (Feature feature : Feature.values()) {
			if (repository.existsById(feature)) continue;
			try {
				repository.saveAndFlush(new FeatureOperation(feature, !initiallyClosed.contains(feature)));
			} catch (DataIntegrityViolationException race) {
				if (!repository.existsById(feature)) throw race;
			}
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void enforceExistingClosure() {
		if (!repository.lock(Feature.LOGIN).orElseThrow(FeatureOperations::unavailable).isOpenForUse()) shutdown.revoke();
	}

	@Override
	@Transactional
	public void requireOpen(Feature feature) {
		if (feature != Feature.LOGIN) {
			FeatureGate.super.requireOpen(feature);
			return;
		}
		// Held through session issuance. A concurrent closure waits for this login or precedes it.
		try {
			var row = entityManager.getReference(FeatureOperation.class, feature);
			// A prior OAuth phase can have loaded this row in the same request.
			entityManager.refresh(row, LockModeType.PESSIMISTIC_READ);
			var state = row.publicState();
			if (!state.open()) throw new BusinessException(GeneralErrorCode.FEATURE_CLOSED, state, state.message());
		} catch (EntityNotFoundException e) {
			throw unavailable();
		}
	}

	@Override
	public FeatureState state(Feature feature) {
		try {
			if (feature == Feature.LOGIN) return repository.findById(feature).orElseThrow(FeatureOperations::unavailable).publicState();
			return snapshot().stream().filter(state -> state.feature() == feature).findFirst().orElseThrow(FeatureOperations::unavailable);
		} catch (DataAccessException | TransactionException e) {
			throw unavailable();
		}
	}

	public List<FeatureState> snapshot() {
		try {
			var values = repository.findAll(Sort.by("feature")).stream()
				.map(FeatureOperation::publicState).toList();
			if (values.size() != Feature.values().length) throw unavailable();
			var master = values.stream().filter(s -> s.feature() == Feature.LOGIN).findFirst().orElseThrow(FeatureOperations::unavailable);
			return values.stream().map(s -> s.feature() == Feature.LOGIN ? s :
				new FeatureState(s.feature(), master.open() && s.open(), master.open() ? s.message() : master.message(),
					master.version() + s.version())).toList();
		} catch (DataAccessException | TransactionException e) {
			throw unavailable();
		}
	}

	public List<AdminState> adminSnapshot() {
		return repository.findAll(Sort.by("feature")).stream().map(AdminState::from).toList();
	}

	@Transactional
	public AdminState change(Feature feature, boolean open, String message, long expectedVersion, long actor) {
		var row = repository.lock(feature).orElseThrow(FeatureOperations::unavailable);
		if (row.getVersion() != expectedVersion) throw new BusinessException(GeneralErrorCode.FEATURE_VERSION_CONFLICT);
		if (feature == Feature.LOGIN && row.isOpenForUse() && !open) shutdown.revoke();
		row.change(open, message, actor);
		repository.flush();
		return AdminState.from(row);
	}

	private static BusinessException unavailable() {
		return new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
	}
}
