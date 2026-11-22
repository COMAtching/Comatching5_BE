package com.comatching.notification.domain.service.fcm;

import com.comatching.notification.domain.delivery.DeliveryNotSentException;

public class FcmDltException extends DeliveryNotSentException {

    public FcmDltException(String message, Throwable cause) {
        super(message, cause);
    }
}
