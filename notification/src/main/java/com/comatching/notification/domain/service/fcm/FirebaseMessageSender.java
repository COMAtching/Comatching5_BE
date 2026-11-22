package com.comatching.notification.domain.service.fcm;

import org.springframework.stereotype.Component;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;

@Component
public class FirebaseMessageSender {

	public String send(Message message) throws FirebaseMessagingException {
		FirebaseMessaging messaging;
		try {
			messaging = FirebaseMessaging.getInstance();
		} catch (IllegalStateException e) {
			throw new FcmDltException("FCM 초기화 오류", e);
		}
		return messaging.send(message);
	}
}
