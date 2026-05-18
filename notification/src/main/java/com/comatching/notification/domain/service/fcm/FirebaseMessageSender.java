package com.comatching.notification.domain.service.fcm;

import org.springframework.stereotype.Component;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;

@Component
public class FirebaseMessageSender {

	public String send(Message message) throws FirebaseMessagingException {
		return FirebaseMessaging.getInstance().send(message);
	}
}
