package com.comatching.common.aop;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

	private final RedissonClient redissonClient;
	private final AopForTransaction aopForTransaction;

	@Around("@annotation(com.comatching.common.annotation.DistributedLock)")
	public Object lock(ProceedingJoinPoint joinPoint) throws Throwable {

		MethodSignature signature = (MethodSignature)joinPoint.getSignature();
		Method method = signature.getMethod();
		DistributedLock distributedLock = method.getAnnotation(DistributedLock.class);

		// 락 키 생성
		String key = distributedLock.key() + ":" + CustomSpringELParser.getDynamicValue(
			signature.getParameterNames(),
			joinPoint.getArgs(),
			distributedLock.identifier()
		);

		RLock rLock = redissonClient.getLock(key);

		try {
			// 락 획득 시도
			boolean available = rLock.tryLock(
				distributedLock.waitTime(),
				distributedLock.leaseTime(),
				distributedLock.timeUnit()
			);

			if (!available) {
				log.warn("락 획득 실패 - Key: {}", key);
				throw new BusinessException(GeneralErrorCode.TOO_MANY_REQUEST);
			}

			log.info("락 획득 성공 - Key: {}", key);

			return aopForTransaction.proceed(joinPoint);
		} catch (InterruptedException e) {
			throw new InterruptedException();
		} finally {
			try {
				if (rLock.isLocked() && rLock.isHeldByCurrentThread()) {
					rLock.unlock();
					log.info("락 해제 성공 - Key: {}", key);
				}
			} catch (IllegalMonitorStateException e) {
				log.error("락 해제 중 오류 발생 - Key: {}", key, e);
			}
		}
	}

}
