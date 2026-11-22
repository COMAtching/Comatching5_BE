package com.comatching.notification.domain.delivery;

/** 수락 여부를 확인할 수 없어 자동 재전송을 보류한다. */
public class DeliveryUncertainException extends RuntimeException {
    public DeliveryUncertainException(String message, Throwable cause) {
        super(message, cause);
    }
}
