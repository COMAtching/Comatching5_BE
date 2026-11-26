package com.comatching.item.domain.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.comatching.common.aop.AopForTransaction;
import com.comatching.common.aop.DistributedLockAspect;
import com.comatching.common.aop.RoleCheckAspect;
import com.comatching.common.domain.enums.ItemRoute;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.common.dto.event.order.RefundCompleted;
import com.comatching.common.dto.item.AddItemRequest;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.handler.GlobalExceptionHandler;
import com.comatching.common.resolver.MemberInfoArgumentResolver;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.item.service.ItemHistoryServiceImpl;
import com.comatching.item.domain.item.service.ItemService;
import com.comatching.item.domain.item.service.ItemServiceImpl;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.entity.OrderGrantLedger;
import com.comatching.item.domain.order.entity.OrderItem;
import com.comatching.item.domain.order.enums.GrantStatus;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.enums.RefundStatus;
import com.comatching.item.domain.order.outbox.OrderGrantOutboxDispatcher;
import com.comatching.item.domain.order.outbox.OrderGrantOutboxRepository;
import com.comatching.item.domain.order.repository.OrderGrantLedgerRepository;
import com.comatching.item.domain.order.repository.OrderRepository;
import com.comatching.item.domain.product.service.AdminPaymentService;
import com.comatching.item.domain.product.service.AdminPaymentServiceImpl;
import com.comatching.item.global.exception.PaymentErrorCode;
import com.comatching.item.infra.controller.admin.AdminPaymentController;
import com.fasterxml.jackson.databind.ObjectMapper;

@Tag("integration")
@Testcontainers
@DirtiesContext
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@EmbeddedKafka(partitions = 1, topics = {ItemsGranted.TOPIC, RefundCompleted.TOPIC})
@ImportAutoConfiguration(KafkaAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = OrderGrantBoundaryIT.Beans.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderGrantBoundaryIT {
	@Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
			.withDatabaseName("comatching_item").withInitScript("04-create-order-grant-outbox.sql")
			.withCommand("--skip-log-bin");
	@Container static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void db(DynamicPropertyRegistry p) {
		p.add("spring.datasource.url", mysql::getJdbcUrl);
		p.add("spring.datasource.username", mysql::getUsername);
		p.add("spring.datasource.password", mysql::getPassword);
	}

	@Configuration
	@EnableJpaAuditing
	@EnableAspectJAutoProxy(proxyTargetClass = true)
	@EntityScan(basePackageClasses = {Order.class, Item.class})
	@EnableJpaRepositories(basePackageClasses = {OrderRepository.class, ItemRepository.class})
	@Import({OrderGrantDispatcher.class, OrderGrantTransaction.class, AdminPaymentServiceImpl.class,
		OrderDecisionLogService.class, OrderOutboxService.class, ItemServiceImpl.class, ItemHistoryServiceImpl.class,
		AopForTransaction.class, DistributedLockAspect.class, OrderGrantOutboxRepository.class, OrderGrantOutboxDispatcher.class,
		AdminPaymentController.class, RoleCheckAspect.class})
	static class Beans {
		@Bean(destroyMethod = "shutdown") RedissonClient redisClient() {
			var config = new Config();
			config.useSingleServer().setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
			return Redisson.create(config);
		}
		@Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
	}

	@Autowired AdminPaymentService admin;
	@Autowired OrderGrantDispatcher worker;
	@Autowired OrderRepository orders;
	@Autowired OrderGrantLedgerRepository ledger;
	@Autowired ItemService items;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager tm;
	@Autowired OrderGrantOutboxRepository outbox;
	@Autowired OrderGrantOutboxDispatcher dispatcher;
	@Autowired KafkaTemplate<String, Object> kafka;
	@Autowired EmbeddedKafkaBroker broker;
	@Autowired ObjectMapper mapper;
	@Autowired AdminPaymentController paymentController;

	@BeforeEach
	void clearOutbox() {
		jdbc.update("DELETE FROM order_grant_outbox_events");
	}

	@Test
	void rollbackOfApprovalDoesNotLeaveAJobOrInventory() {
		long member = 303;
		long id = newOrder(member);
		new TransactionTemplate(tm).executeWithoutResult(tx -> {
			admin.approvePurchase(id, 900L);
			assertThat(admin.getGrant(id).grantStatus()).isEqualTo(GrantStatus.PENDING);
			tx.setRollbackOnly();
		});

		worker.processOne(id);
		var grant = admin.getGrant(id);
		assertThat(grant.status()).isEqualTo(OrderStatus.PENDING);
		assertThat(grant.grantStatus()).isEqualTo(GrantStatus.NOT_REQUESTED);
		assertThat(grant.attempt()).isZero();
		assertThat(count("item", member)).isZero();
		assertThat(count("item_history", member)).isZero();
		assertThat(count("order_grant_ledger", member)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_decision_logs WHERE order_id=?", Long.class, id)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox_events WHERE aggregate_id=?", Long.class, id)).isZero();
		assertThat(grantEventCount(id)).isZero();
	}

	@Test
	void historicalOrderUnderReviewCannotBeApprovedOrRetried() {
		long member = 404;
		long id = newOrder(member);
		jdbc.update("UPDATE purchase_order SET grant_status='REVIEW_REQUIRED' WHERE id=?", id);
		assertThatThrownBy(() -> admin.approvePurchase(id, 900L)).isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException) ex).getErrorCode()).isEqualTo(PaymentErrorCode.GRANT_REVIEW_REQUIRED);
		assertThatThrownBy(() -> admin.retryGrant(id)).isInstanceOf(BusinessException.class);
		worker.processOne(id);
		assertThat(admin.getGrant(id).grantStatus()).isEqualTo(GrantStatus.REVIEW_REQUIRED);
		assertThat(count("item", member)).isZero();
	}

	@Test
	void legacyNestedAddCommitsStockEvenWhenOuterApprovalRollsBack() {
		long member = 101;
		long id = newOrder(member);
		var tx = new TransactionTemplate(tm);
		assertThatThrownBy(() -> tx.executeWithoutResult(st -> {
			var order = orders.findByIdWithItems(id).orElseThrow();
			var line = order.getOrderItems().get(0);
			orders.updateStatusIfCurrentAndNotExpired(id, OrderStatus.PENDING, OrderStatus.APPROVED,
				LocalDateTime.now(), LocalDateTime.now());
			ledger.saveAndFlush(OrderGrantLedger.builder().orderId(id).orderItemId(line.getId()).memberId(member)
				.itemType(line.getItemType()).quantity(line.getQuantity()).grantedAt(LocalDateTime.now()).build());
			items.addItem(member, new AddItemRequest(line.getItemType(), line.getQuantity(), ItemRoute.CHARGE, 36500));
			throw new IllegalStateException("승인 이력 저장 실패 예시");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(count("order_grant_ledger", member)).isZero();
		assertThat(count("item", member)).isOne();
		assertThat(count("item_history", member)).isOne();
	}

	@Test
	void bundleRollsBackTogetherAndTwoRetryWorkersGrantOnlyOnce() throws Exception {
		long member = 202;
		long id = newOrder(member);
		admin.approvePurchase(id, 900L);
		assertThat(admin.getGrant(id).grantStatus()).isEqualTo(GrantStatus.PENDING);
		assertThat(count("item", member)).isZero();
		assertThat(grantEventCount(id)).isZero();
		jdbc.execute("""
			CREATE TRIGGER test_second_grant BEFORE INSERT ON item_history FOR EACH ROW
			BEGIN
				IF (SELECT COUNT(*) FROM order_grant_ledger WHERE member_id=NEW.member_id) >= 2 THEN
					SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='second line history failed';
				END IF;
			END
			""");
		try {
			worker.dispatch();
		} finally {
			jdbc.execute("DROP TRIGGER test_second_grant");
		}
		assertThat(admin.getGrant(id).status()).isEqualTo(OrderStatus.APPROVED);
		assertThat(admin.getGrant(id).grantStatus()).isEqualTo(GrantStatus.FAILED);
		assertThat(grantEventCount(id)).isZero();
		for (var table : List.of("order_grant_ledger", "item", "item_history")) {
			assertThat(count(table, member)).as(table).isZero();
		}

		admin.retryGrant(id);
		assertThat(orders.failGrant(id, 1)).isZero(); // 이 전송 시도는 이미 끝났다.
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var a = pool.submit(() -> { start.await(); worker.processOne(id); return null; });
			var b = pool.submit(() -> { start.await(); worker.processOne(id); return null; });
			start.countDown();
			a.get(20, TimeUnit.SECONDS);
			b.get(20, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertThat(admin.getGrant(id).grantStatus()).isEqualTo(GrantStatus.GRANTED);
		assertThatThrownBy(() -> admin.retryGrant(id)).isInstanceOf(BusinessException.class);
		for (var table : List.of("order_grant_ledger", "item", "item_history")) {
			assertThat(count(table, member)).as(table).isEqualTo(2);
		}
		assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM item WHERE member_id=?", Integer.class, member)).isEqualTo(3);
		assertThat(grantEventCount(id)).isOne();
		assertThat(grantEventStatus(id)).isEqualTo("PENDING");
	}

	@Test
	void outboxWriteFailureRollsBackBothItemsAndKeepsApproval() {
		long member = 505;
		long id = newOrder(member);
		admin.approvePurchase(id, 900L);
		jdbc.execute("""
				CREATE TRIGGER test_grant_outbox BEFORE INSERT ON order_grant_outbox_events
				FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='outbox insert failed'
				""");
		try {
			worker.processOne(id);
		} finally {
			jdbc.execute("DROP TRIGGER test_grant_outbox");
		}
		assertThat(admin.getGrant(id).status()).isEqualTo(OrderStatus.APPROVED);
		assertThat(admin.getGrant(id).grantStatus()).isEqualTo(GrantStatus.FAILED);
		assertThat(grantEventCount(id)).isZero();
		for (String table : List.of("order_grant_ledger", "item", "item_history")) {
			assertThat(count(table, member)).as(table).isZero();
		}
	}

	@Test
	void lostKafkaAcknowledgementRetriesWithTheSameOrderKeyAndEvent() throws Exception {
		long member = 606;
		long id = newOrder(member);
		admin.approvePurchase(id, 900L);
		worker.processOne(id);
		String key = Long.toString(id);
		String payload = jdbc.queryForObject("SELECT payload FROM order_grant_outbox_events WHERE order_id=?", String.class, id);
		var event = mapper.readValue(payload, ItemsGranted.class);
		assertThat(event.eventId()).isEqualTo("items-granted:" + id);
		assertThat(event.orderId()).isEqualTo(id);
		assertThat(event.memberId()).isEqualTo(member);
		assertThat(event.grantedAt()).isNotNull();
		assertThat(event.items()).containsExactlyInAnyOrder(
				new ItemsGranted.GrantedItem(ItemType.MATCHING_TICKET, 2), new ItemsGranted.GrantedItem(ItemType.OPTION_TICKET, 1));

		// 브로커에는 실제로 기록하되 발행기에는 ACK 대신 타임아웃을 전달한다.
		KafkaTemplate<String, Object> lostAck = mock();
		when(lostAck.send(eq(ItemsGranted.TOPIC), eq(key), any(byte[].class))).thenAnswer(call ->
				kafka.send(ItemsGranted.TOPIC, key, (byte[]) call.getArgument(2))
						.thenCompose(ack -> CompletableFuture.failedFuture(new TimeoutException("ack lost"))));
		new OrderGrantOutboxDispatcher(outbox, lostAck).dispatch();
		assertThat(admin.getGrant(id).grantStatus()).isEqualTo(GrantStatus.GRANTED);
		assertThat(grantEventStatus(id)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT retry_count FROM order_grant_outbox_events WHERE order_id=?", Integer.class, id)).isOne();

		jdbc.update("UPDATE order_grant_outbox_events SET available_at=NOW(6) WHERE order_id=?", id);
		dispatcher.dispatch();
		assertThat(grantEventStatus(id)).isEqualTo("PUBLISHED");
		assertThat(jdbc.queryForObject("SELECT published_at FROM order_grant_outbox_events WHERE order_id=?", LocalDateTime.class, id)).isNotNull();
		var props = KafkaTestUtils.consumerProps("grant-retry-probe", "false", broker);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (var consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
			broker.consumeFromAnEmbeddedTopic(consumer, ItemsGranted.TOPIC);
			var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 2).records(ItemsGranted.TOPIC);
			assertThat(records).hasSize(2).allSatisfy(record -> {
				assertThat(record.key()).isEqualTo(key);
				assertThat(record.value()).isEqualTo(payload);
			});
		}
	}

	private long grantEventCount(long orderId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM order_grant_outbox_events WHERE order_id=?", Long.class, orderId);
	}

	@Test
	@Transactional
	void adminCanPageFailedApprovedOrdersAndRetryRemovesAnOrderFromTheList() throws Exception {
		MockMvc mvc = adminMvc();
		String path = "/api/v1/admin/payment/grants";
		long existing = admin.getFailedGrants(0, 1).totalElements();
		long first = newOrder(701);
		long second = newOrder(702);
		for (long id : List.of(first, second)) {
			admin.approvePurchase(id, 900L);
			assertThat(orders.failGrant(id, 1)).isOne();
		}
		// 이미 입금된 주문은 승인 요청의 유효 시간이 지나도 복구 대상이다.
		jdbc.update("UPDATE purchase_order SET expires_at=? WHERE id=?", LocalDateTime.now().minusHours(1), second);
		long unapproved = newOrder(703);
		jdbc.update("UPDATE purchase_order SET grant_status='FAILED' WHERE id=?", unapproved);
		admin.approvePurchase(newOrder(704), 900L); // 지급 대기
		long granted = newOrder(705);
		admin.approvePurchase(granted, 900L);
		worker.processOne(granted);
		assertThat(admin.getGrant(granted).grantStatus()).isEqualTo(GrantStatus.GRANTED);
		long review = newOrder(706);
		jdbc.update("UPDATE purchase_order SET status='APPROVED', grant_status='REVIEW_REQUIRED' WHERE id=?", review);

		mvc.perform(get(path).param("grantStatus", "FAILED").param("size", "1")
						.header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content.length()").value(1))
				.andExpect(jsonPath("$.data.content[0].orderId").value(second))
				.andExpect(jsonPath("$.data.content[0].requestedItemName").value("묶음"))
				.andExpect(jsonPath("$.data.content[0].status").value("APPROVED"))
				.andExpect(jsonPath("$.data.content[0].grantStatus").value("FAILED"))
				.andExpect(jsonPath("$.data.content[0].attempt").value(1))
				.andExpect(jsonPath("$.data.totalElements").value(existing + 2))
				.andExpect(jsonPath("$.data.hasNext").value(true));
		assertThat(admin.getFailedGrants(1, 1).content().get(0).orderId()).isEqualTo(first);

		mvc.perform(post("/api/v1/admin/payment/requests/{id}/grant/retry", second)
						.header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isAccepted());
		mvc.perform(get(path).header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.content[0].orderId").value(first))
				.andExpect(jsonPath("$.data.size").value(20))
				.andExpect(jsonPath("$.data.totalElements").value(existing + 1));
		assertThat(admin.getFailedGrants(0, 150).size()).isEqualTo(100);
		assertThat(admin.getFailedGrants((int) existing + 3, 1).content()).isEmpty();
	}

	@Test
	void failedGrantsApiRequiresAdminAndRejectsInvalidFiltersOrPages() throws Exception {
		MockMvc mvc = adminMvc();
		String path = "/api/v1/admin/payment/grants";
		mvc.perform(get(path).header("X-Member-Id", "701").header("X-Member-Role", "ROLE_USER"))
				.andExpect(status().isForbidden());
		for (String query : List.of("?grantStatus=PENDING", "?grantStatus=INVALID", "?page=-1", "?size=0")) {
			mvc.perform(get(path + query).header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
					.andExpect(status().isBadRequest());
		}
	}

	private MockMvc adminMvc() {
		return MockMvcBuilders.standaloneSetup(paymentController)
				.setCustomArgumentResolvers(new MemberInfoArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler(mapper))
				.build();
	}

	@Test
	void manualRefundApisExcludeGrantsAndPublishTheConfirmedAmount() throws Exception {
		long id = failedOrder(801);
		MockMvc mvc = adminMvc();
		String url = "/api/v1/admin/payment/requests/" + id + "/refund/";
		for (String action : List.of("start", "complete")) {
			mvc.perform(post(url + action).header("X-Member-Id", "4").header("X-Member-Role", "ROLE_USER"))
					.andExpect(status().isForbidden());
		}
		mvc.perform(post(url + "complete").header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isConflict());
		mvc.perform(post(url + "start").header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.refundStatus").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.data.amount").value(1000)).andExpect(jsonPath("$.data.startedBy").value(900));
		mvc.perform(post(url + "start").header("X-Member-Id", "901").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isConflict());
		mvc.perform(get("/api/v1/admin/payment/refunds").header("X-Member-Id", "900").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].orderId").value(id));
		assertThat(admin.getFailedGrants(0, 100).content()).noneMatch(row -> row.orderId() == id);
		assertThatThrownBy(() -> admin.retryGrant(id)).isInstanceOf(BusinessException.class);
		assertThat(grantEventCount(id)).isZero();
		mvc.perform(post(url + "complete").header("X-Member-Id", "901").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.refundStatus").value("COMPLETED"))
				.andExpect(jsonPath("$.data.completedBy").value(901));
		mvc.perform(post(url + "complete").header("X-Member-Id", "902").header("X-Member-Role", "ROLE_ADMIN"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.completedBy").value(901));
		assertThat(admin.getGrant(id).refundStatus()).isEqualTo(RefundStatus.COMPLETED);
		assertThatThrownBy(() -> admin.retryGrant(id)).isInstanceOf(BusinessException.class);
		worker.processOne(id);
		assertThat(ledger.existsByOrderId(id)).isFalse();
		assertThat(grantEventCount(id)).isOne();
		assertThat(orders.existsApprovedOrActivePendingOrder(801L, LocalDateTime.now())).isFalse();
		assertThat(orders.sumApprovedPriceByMemberIdAndDecidedAtBetween(801L, LocalDateTime.now().minusDays(1),
				LocalDateTime.now().plusDays(1))).isZero();
		dispatcher.dispatch();
		assertThat(grantEventStatus(id)).isEqualTo("PUBLISHED");
		var props = KafkaTestUtils.consumerProps("refund-probe", "false", broker);
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (var consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
			broker.consumeFromAnEmbeddedTopic(consumer, RefundCompleted.TOPIC);
			var record = KafkaTestUtils.getSingleRecord(consumer, RefundCompleted.TOPIC, Duration.ofSeconds(10));
			assertThat(record.key()).isEqualTo(Long.toString(id));
			var event = mapper.readValue(record.value(), RefundCompleted.class);
			assertThat(event.eventId()).isEqualTo("refund-completed:" + id);
			assertThat(event.orderId()).isEqualTo(id);
			assertThat(event.amount()).isEqualTo(1000);
			assertThat(event.memberId()).isEqualTo(801);
		}
	}

	@Test
	void refundRequiresApprovedFailedOrderWithoutGrantEvidence() {
		long id = newOrder(802);
		assertThatThrownBy(() -> admin.startRefund(id, 900L)).isInstanceOf(BusinessException.class);
		admin.approvePurchase(id, 900L);
		assertThatThrownBy(() -> admin.startRefund(id, 900L)).isInstanceOf(BusinessException.class);
		worker.processOne(id);
		assertThat(admin.getGrant(id).grantStatus()).isEqualTo(GrantStatus.GRANTED);
		assertThatThrownBy(() -> admin.startRefund(id, 900L)).isInstanceOf(BusinessException.class);
		// 과거 데이터의 상태가 잘못돼 있어도 이미 지급된 원장 또는 outbox가 있으면 보류한다.
		jdbc.update("UPDATE purchase_order SET grant_status='FAILED' WHERE id=?", id);
		assertThatThrownBy(() -> admin.startRefund(id, 900L)).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(PaymentErrorCode.GRANT_REVIEW_REQUIRED);
		jdbc.update("DELETE FROM order_grant_ledger WHERE order_id=?", id);
		assertThatThrownBy(() -> admin.startRefund(id, 900L)).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(PaymentErrorCode.GRANT_REVIEW_REQUIRED);
		assertThat(admin.getGrant(id).refundStatus()).isEqualTo(RefundStatus.NONE);
		jdbc.update("UPDATE purchase_order SET grant_status='REVIEW_REQUIRED' WHERE id=?", id);
		assertThatThrownBy(() -> admin.startRefund(id, 900L)).isInstanceOf(BusinessException.class);
	}

	@Test
	void retryAndRefundCompeteForTheSameOrder() throws Exception {
		long id = failedOrder(803);
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var refund = pool.submit(() -> {
				start.await();
				return accepted(() -> admin.startRefund(id, 900L), PaymentErrorCode.REFUND_NOT_STARTABLE);
			});
			var retry = pool.submit(() -> {
				start.await();
				return accepted(() -> admin.retryGrant(id), PaymentErrorCode.GRANT_NOT_RETRYABLE);
			});
			start.countDown();
			boolean refundWon = refund.get(10, TimeUnit.SECONDS);
			assertThat(retry.get(10, TimeUnit.SECONDS)).isEqualTo(!refundWon);
			var state = admin.getGrant(id);
			assertThat(state.refundStatus()).isEqualTo(refundWon ? RefundStatus.IN_PROGRESS : RefundStatus.NONE);
			assertThat(state.grantStatus()).isEqualTo(refundWon ? GrantStatus.FAILED : GrantStatus.PENDING);
			if (!refundWon) worker.processOne(id);
		} finally {
			pool.shutdownNow();
		}
	}

	private boolean accepted(Runnable work, PaymentErrorCode conflict) {
		try {
			work.run();
			return true;
		} catch (BusinessException e) {
			assertThat(e.getErrorCode()).isEqualTo(conflict);
			return false;
		}
	}

	@Test
	void failedMailEnqueueRollsBackConfirmationAndConcurrentConfirmationsCreateOneEvent() throws Exception {
		long id = failedOrder(804);
		admin.startRefund(id, 900L);
		jdbc.execute("CREATE TRIGGER test_refund_outbox BEFORE INSERT ON order_grant_outbox_events "
				+ "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='refund outbox unavailable'");
		try {
			assertThatThrownBy(() -> admin.completeRefund(id, 901L)).isInstanceOf(org.springframework.dao.DataAccessException.class);
		} finally {
			jdbc.execute("DROP TRIGGER test_refund_outbox");
		}
		assertThat(admin.getGrant(id).refundStatus()).isEqualTo(RefundStatus.IN_PROGRESS);
		assertThat(grantEventCount(id)).isZero();
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			var first = pool.submit(() -> { start.await(); return admin.completeRefund(id, 901L); });
			var second = pool.submit(() -> { start.await(); return admin.completeRefund(id, 902L); });
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
		assertThat(grantEventCount(id)).isOne();
		assertThat(admin.getGrant(id).refundStatus()).isEqualTo(RefundStatus.COMPLETED);
	}

	private long failedOrder(long member) {
		long id = newOrder(member);
		admin.approvePurchase(id, 900L);
		assertThat(orders.failGrant(id, 1)).isOne();
		return id;
	}

	private String grantEventStatus(long orderId) {
		return jdbc.queryForObject("SELECT status FROM order_grant_outbox_events WHERE order_id=?", String.class, orderId);
	}

	private long count(String table, long member) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE member_id=?", Long.class, member);
	}

	@Test
	void productUnitsRespectMixedLegacyOrdersRefundsExpiryAndBundledLines() {
		long member = 93201L;
		String code = "LIMITED_BUNDLE";
		var now = LocalDateTime.now();
		long legacy = unitsOrder(member, code, null, now.plusMinutes(1));
		long recent = unitsOrder(member, code, 2, now.plusMinutes(1));
		unitsOrder(member, code, 8, now.minusMinutes(1));
		assertThat(orders.sumPendingProductUnits(member, code, now)).isEqualTo(3);
		assertThat(orders.sumPendingDiscountUnits(member, code, ItemType.MATCHING_TICKET, now)).isEqualTo(5);
		jdbc.update("UPDATE purchase_order SET status='APPROVED' WHERE id IN (?,?)", legacy, recent);
		assertThat(orders.sumApprovedProductUnits(member, code)).isEqualTo(3);
		assertThat(orders.sumApprovedDiscountUnits(member, code, ItemType.MATCHING_TICKET)).isEqualTo(5);
		jdbc.update("UPDATE purchase_order SET refund_status='COMPLETED' WHERE id=?", recent);
		assertThat(orders.sumApprovedProductUnits(member, code)).isEqualTo(1);
		assertThat(orders.sumApprovedDiscountUnits(member, code, ItemType.MATCHING_TICKET)).isEqualTo(3);
	}

	private long unitsOrder(long member, String code, Integer units, LocalDateTime expiresAt) {
		Order order = Order.builder().memberId(member).productCode(code).productQuantity(units)
			.requestedItemName("구매 한도").requesterRealName("test").requesterUsername("test")
			.requestedPrice(800).expectedPrice(800).requestedAt(LocalDateTime.now()).expiresAt(expiresAt).build();
		order.addOrderItem(OrderItem.builder().itemType(ItemType.MATCHING_TICKET).quantity(3).build());
		order.addOrderItem(OrderItem.builder().itemType(ItemType.OPTION_TICKET).quantity(2).build());
		return orders.save(order).getId();
	}

	private long newOrder(long member) {
		var now = LocalDateTime.now();
		Order order = Order.builder().memberId(member).requestedItemName("묶음").requesterRealName("test")
			.requesterUsername("test").requestedPrice(1000).expectedPrice(1000)
			.requestedAt(now).expiresAt(now.plusMinutes(30)).build();
		order.addOrderItem(OrderItem.builder().itemType(ItemType.MATCHING_TICKET).quantity(2).build());
		order.addOrderItem(OrderItem.builder().itemType(ItemType.OPTION_TICKET).quantity(1).build());
		return orders.save(order).getId();
	}
}
