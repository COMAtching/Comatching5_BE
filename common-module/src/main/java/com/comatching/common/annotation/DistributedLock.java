package com.comatching.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

	/**
	 * 락의 이름 (Key)
	 */
	String key();

	/**
	 * 락의 식별자 (SpEL 표현식)
	 */
	String identifier();

	/**
	 * 락 획득을 위해 대기하는 시간 (기본 5초)
	 */
	long waitTime() default 5L;

	/**
	 * 락을 잡고 있는 최대 시간 (기본 3초)
	 */
	long leaseTime() default 3L;

	/**
	 * 시간 단위
	 */
	TimeUnit timeUnit() default TimeUnit.SECONDS;
}
