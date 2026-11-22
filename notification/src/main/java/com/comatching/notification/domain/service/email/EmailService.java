package com.comatching.notification.domain.service.email;

import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.common.dto.event.order.RefundCompleted;

public interface EmailService {

	void sendWelcomeEmail(String toEmail, String nickname);
	void sendWithdrawalEmail(String toEmail);

	void sendItemsGranted(String toEmail, ItemsGranted event);

	void sendRefundCompleted(String toEmail, RefundCompleted event);
}
