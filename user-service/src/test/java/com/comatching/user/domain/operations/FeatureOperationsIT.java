package com.comatching.user.domain.operations;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.common.operations.Feature;
import com.comatching.user.support.OutboxMySqlSupport;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "operations.initially-closed=SHOP_PAYMENT"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = FeatureOperationsIT.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("integration")
class FeatureOperationsIT extends OutboxMySqlSupport {
	@Configuration
	@EntityScan(basePackageClasses = FeatureOperation.class)
	@EnableJpaRepositories(basePackageClasses = FeatureOperationRepository.class)
	@Import(FeatureOperations.class)
	static class Config {}

	@org.springframework.test.context.bean.override.mockito.MockitoBean MemberSessionShutdown shutdown;
	@Autowired FeatureOperations service;

	@Test
	void concurrentAdministratorsCannotOverwriteOneAnotherAndRestartKeepsChoices() throws Exception {
		service.run(null);
		assertThat(service.snapshot()).hasSize(4);
		assertThatThrownBy(() -> service.requireOpen(Feature.SHOP_PAYMENT)).isInstanceOf(BusinessException.class);
		var initial = service.state(Feature.CHAT);
		var start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Boolean> change = () -> {
				start.await();
				try {
					service.change(Feature.CHAT, false, "기존 대화를 볼 수 있습니다.", initial.version(), 7);
					return true;
				} catch (BusinessException e) {
					assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.FEATURE_VERSION_CONFLICT);
					return false;
				}
			};
			var left = pool.submit(change);
			var right = pool.submit(change);
			start.countDown();
			assertThat(List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(true, false);
		} finally { pool.shutdownNow(); }
		service.run(null);
		assertThat(service.adminSnapshot()).anySatisfy(value -> {
			assertThat(value.feature()).isEqualTo(Feature.CHAT);
			assertThat(value.open()).isFalse();
			assertThat(value.changedBy()).isEqualTo(7);
		});
		assertThatThrownBy(() -> service.requireOpen(Feature.CHAT)).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.FEATURE_CLOSED));
		service.requireOpen(Feature.MATCHING);
		service.change(Feature.CHAT, true, "", service.state(Feature.CHAT).version(), 9);
		service.requireOpen(Feature.CHAT);
	}
}
