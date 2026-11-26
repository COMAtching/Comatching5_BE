package com.comatching.item.infra.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comatching.item.domain.order.dto.MyPurchaseOrder;
import com.comatching.item.domain.order.repository.MyPurchaseOrderQuery;
import com.comatching.item.domain.order.repository.MyPurchaseOrderQuery.Scope;

class MyPurchaseOrdersTest {
	private JdbcTemplate db;
	private MyPurchaseOrderQuery orders;
	private final LocalDateTime now = LocalDateTime.now();

	@BeforeEach
	void setup() {
		db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
		db.execute("""
			CREATE TABLE purchase_order(id BIGINT PRIMARY KEY, member_id BIGINT, requested_item_name VARCHAR,
			product_quantity INT, expected_price INT, requested_at TIMESTAMP, expires_at TIMESTAMP,
			requester_real_name VARCHAR, status VARCHAR, grant_status VARCHAR, refund_status VARCHAR)
			""");
		orders = new MyPurchaseOrderQuery(JdbcClient.create(db));
	}

	void insert(int id, int owner, String status, String grant, String refund, LocalDateTime expires) {
		db.update("INSERT INTO purchase_order VALUES(?, ?, '테스트 번들', NULL, 1000, ?, ?, '신청 당시 이름', ?, ?, ?)",
			id, owner, now, expires, status, grant, refund);
	}

	@Test
	void activePrioritizesPayableOrdersAndScopesPagingToMember() throws Exception {
		insert(1, 10, "PENDING", "NOT_REQUESTED", "NONE", now.plusMinutes(1));
		insert(2, 10, "APPROVED", "PENDING", "NONE", now);
		insert(3, 10, "PENDING", "NOT_REQUESTED", "NONE", now);
		insert(4, 10, "PENDING", "REVIEW_REQUIRED", "NONE", now.plusDays(1));
		insert(5, 11, "PENDING", "NOT_REQUESTED", "NONE", now.plusDays(1));
		var first = orders.find(10, Scope.ACTIVE, PageRequest.of(0, 2));
		assertThat(first.totalElements()).isEqualTo(3);
		assertThat(first.hasNext()).isTrue();
		assertThat(first.content()).extracting(MyPurchaseOrder::orderId).containsExactly(1L, 4L);
		assertThat(orders.find(10, Scope.ACTIVE, PageRequest.of(1, 2)).content())
			.extracting(MyPurchaseOrder::orderId).containsExactly(2L);
		var mvc = MockMvcBuilders.standaloneSetup(new MyPurchaseOrderController(orders))
			.setCustomArgumentResolvers(new MemberInfoArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper())).build();
		mvc.perform(get("/api/v1/shop/purchase/orders").header("X-Member-Id", "10").param("memberId", "11"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(4))
			.andExpect(jsonPath("$.data.content[0].status").value("REVIEW_REQUIRED"))
			.andExpect(jsonPath("$.data.content[0].depositorName").doesNotHaveJsonPath())
			.andExpect(jsonPath("$.data.content[1].status").value("EXPIRED"))
			.andExpect(jsonPath("$.data.content[1].depositorName").doesNotHaveJsonPath())
			.andExpect(jsonPath("$.data.content[3].productName").value("테스트 번들"))
			.andExpect(jsonPath("$.data.content[3].quantity").isEmpty())
			.andExpect(jsonPath("$.data.content[3].depositorName").value("신청 당시 이름"));
		mvc.perform(get("/api/v1/shop/purchase/orders").header("X-Member-Id", "10").param("size", "0"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void approvalGrantRetryAndRefundHaveDistinctVisibleStates() {
		insert(1, 10, "APPROVED", "PENDING", "NONE", now);
		check("GRANTING", true);
		update("FAILED", "NONE");
		check("GRANT_DELAYED", true);
		update("PENDING", "NONE");
		check("GRANTING", true);
		update("GRANTED", "NONE");
		check("GRANTED", false);
		update("REVIEW_REQUIRED", "NONE");
		check("REVIEW_REQUIRED", true);
		update("FAILED", "IN_PROGRESS");
		check("REFUNDING", true);
		update("FAILED", "COMPLETED");
		check("REFUNDED", false);
		db.update("UPDATE purchase_order SET status='REJECTED', refund_status='NONE'");
		check("REJECTED", false);
		db.update("UPDATE purchase_order SET status='CANCELED'");
		check("CANCELED", false);
	}

	private void update(String grant, String refund) {
		db.update("UPDATE purchase_order SET grant_status=?, refund_status=?", grant, refund);
	}
	private void check(String expected, boolean active) {
		var page = PageRequest.of(0, 20);
		var order = orders.find(10, Scope.ALL, page).content().get(0);
		assertThat(order.status()).isEqualTo(expected);
		assertThat(order.depositorName()).isNull();
		assertThat(orders.find(10, Scope.ACTIVE, page).totalElements()).isEqualTo(active ? 1 : 0);
	}
}
