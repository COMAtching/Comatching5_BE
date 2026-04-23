package com.comatching.item.domain.product.repository;

import com.comatching.item.domain.product.entity.PurchaseRequest;
import com.comatching.item.domain.product.enums.PurchaseStatus;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, Long> {
	List<PurchaseRequest> findAllByStatusOrderByRequestedAtDesc(PurchaseStatus status);

	boolean existsByMemberIdAndStatus(Long memberId, PurchaseStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT pr FROM PurchaseRequest pr WHERE pr.id = :requestId")
	Optional<PurchaseRequest> findByIdForUpdate(@Param("requestId") Long requestId);
}
