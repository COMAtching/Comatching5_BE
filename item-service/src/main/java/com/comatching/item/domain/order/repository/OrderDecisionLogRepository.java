package com.comatching.item.domain.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.comatching.item.domain.order.entity.OrderDecisionLog;

public interface OrderDecisionLogRepository extends JpaRepository<OrderDecisionLog, Long> {
}
