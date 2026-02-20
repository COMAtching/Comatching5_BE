package com.comatching.item.domain.product.repository;

import com.comatching.item.domain.product.entity.PurchaseRequest;
import com.comatching.item.domain.product.enums.PurchaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, Long> {
	List<PurchaseRequest> findAllByStatusOrderByRequestedAtDesc(PurchaseStatus status);
}