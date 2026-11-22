package com.comatching.item.domain.order.service;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.enums.GrantStatus;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderGrantDispatcher {
	private final OrderRepository orders;
	private final OrderGrantTransaction grants;
	private final RedissonClient redis;

	@Scheduled(fixedDelayString = "${comatching.order.grant-poll-ms:1000}")
	public void dispatch() {
		for (Long id : orders.findPendingGrantIds(PageRequest.of(0, 50))) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			processOne(id);
		}
	}

	// 이 메서드는 트랜잭션 밖에서 실행한다. 구성품은 주문 생성 후 변경되지 않는다.
	public void processOne(Long id) {
		var snapshot = orders.findByIdWithItems(id);
		if (snapshot.isEmpty()) {
			return;
		}
		Order order = snapshot.get();
		if (order.getStatus() != OrderStatus.APPROVED || order.getGrantStatus() != GrantStatus.PENDING) {
			return;
		}
		int attempt = order.getGrantAttempt();
		try {
			RLock[] locks = order.getOrderItems().stream()
					.map(line -> "item:inventory:" + order.getMemberId() + ":" + line.getItemType())
					.distinct().sorted().map(redis::getLock).toArray(RLock[]::new);
			if (locks.length == 0) {
				throw new IllegalStateException("구성품 없는 주문: " + id);
			}
			RLock inventory = locks.length == 1 ? locks[0] : redis.getMultiLock(locks);
			if (!inventory.tryLock(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("재고 락 획득 실패");
			}
			try {
				grants.grant(id, attempt); // 별도 Spring 빈이므로 반환 시점에 DB 커밋까지 끝난다.
			} finally {
				inventory.unlock();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); // 다음 실행에서 PENDING을 다시 찾는다.
		} catch (RuntimeException e) {
			// grant() 롤백 뒤 별도 트랜잭션. 이미 GRANTED이거나 새로운 시도면 0행.
			int changed = orders.failGrant(id, attempt);
			log.error("주문 지급 오류 orderId={} attempt={} markedFailed={}", id, attempt, changed, e);
		}
	}
}
