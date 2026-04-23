package com.comatching.item.domain.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.comatching.item.domain.order.entity.OrderGrantLedger;

public interface OrderGrantLedgerRepository extends JpaRepository<OrderGrantLedger, Long> {

	boolean existsByOrderIdAndOrderItemId(Long orderId, Long orderItemId);
}
