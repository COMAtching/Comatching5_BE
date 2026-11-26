package com.comatching.user.domain.operations;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.comatching.common.operations.Feature;

public interface FeatureOperationRepository extends JpaRepository<FeatureOperation, Feature> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select f from FeatureOperation f where f.feature = :feature")
	Optional<FeatureOperation> lock(@Param("feature") Feature feature);
}
