package com.comatching.notification.domain.delivery;

/** 외부 호출 전 실패했거나 제공자가 요청을 명시적으로 거절했다. */
public class DeliveryNotSentException extends RuntimeException {
    public DeliveryNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
