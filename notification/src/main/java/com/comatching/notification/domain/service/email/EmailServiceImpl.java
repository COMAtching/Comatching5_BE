package com.comatching.notification.domain.service.email;

import java.net.ConnectException;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.common.dto.event.order.RefundCompleted;
import com.comatching.notification.domain.delivery.DeliveryNotSentException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import jakarta.mail.MessagingException;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService{

	private final JavaMailSender mailSender;
	private final SpringTemplateEngine templateEngine;

	@Override
	public void sendItemsGranted(String toEmail, ItemsGranted event) {
		String items = event.items().stream()
				.map(line -> line.itemType().getName() + " " + line.quantity() + "장")
				.collect(Collectors.joining(", "));
		sendOrderEmail(toEmail, "구매한 아이템이 지급됐어요",
				"주문 " + event.orderId() + "의 " + items + " 지급이 완료됐어요. 지금 사용할 수 있어요.");
	}

	@Override
	public void sendRefundCompleted(String toEmail, RefundCompleted event) {
		sendOrderEmail(toEmail, "주문 환불이 완료됐어요",
				"주문 " + event.orderId() + "의 환불 금액 " + event.amount() + "원을 계좌이체했어요.");
	}

	private void sendOrderEmail(String toEmail, String subject, String body) {
		var message = new SimpleMailMessage();
		message.setTo(toEmail);
		message.setSubject(subject);
		message.setText(body);
		sendToSmtp(toEmail, () -> mailSender.send(message));
	}

	private void sendToSmtp(String toEmail, Runnable send) {
		try {
			send.run();
		} catch (MailAuthenticationException | MailParseException e) {
			throw new DeliveryNotSentException("SMTP 인증 또는 메시지 작성 실패", e);
		} catch (MailSendException e) {
			if (e.contains(ConnectException.class) || Arrays.stream(e.getMessageExceptions())
					.anyMatch(failure -> explicitlyRejected(failure, toEmail))) {
				throw new DeliveryNotSentException("SMTP 연결 거부 또는 수신자 거절", e);
			}
			throw e; // DATA 전송 후 응답 유실 등 수락 여부가 불명확한 경우
		}
	}

	private boolean explicitlyRejected(Exception failure, String recipient) {
		if (!(failure instanceof SendFailedException rejected)) return false;
		Address[] sent = rejected.getValidSentAddresses();
		if (sent != null && sent.length > 0) return false;
		if (rejected instanceof SMTPSendFailedException smtp) {
			// Angus는 본문 전송 후 응답을 잃어도 수신자를 validUnsent에 넣을 수 있다.
			int reply = smtp.getReturnCode();
			if (reply < 400 || reply >= 600) return false;
		}
		return containsRecipient(rejected.getInvalidAddresses(), recipient)
				|| containsRecipient(rejected.getValidUnsentAddresses(), recipient);
	}

	private boolean containsRecipient(Address[] addresses, String recipient) {
		return addresses != null && Arrays.stream(addresses).anyMatch(address ->
				address instanceof InternetAddress mail && recipient.equalsIgnoreCase(mail.getAddress()));
	}

	@Override
	public void sendWelcomeEmail(String toEmail, String nickname) {
		sendEmail(toEmail, "Comatching 회원가입을 축하합니다! 🎉", "welcome-email", nickname);
	}

	@Override
	public void sendWithdrawalEmail(String toEmail) {
		sendEmail(toEmail, "Comatching 이용해 주셔서 감사합니다.", "goodbye-email", null);
	}

	private void sendEmail(String toEmail, String subject, String templateName, String nickname) {
		MimeMessage mimeMessage;
		try {
			mimeMessage = mailSender.createMimeMessage();
			Context context = new Context();
			context.setVariable("nickname", nickname != null ? nickname : "회원");

			String htmlContent = templateEngine.process(templateName, context);

			MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
			helper.setTo(toEmail);
			helper.setSubject(subject);
			helper.setText(htmlContent, true);
		} catch (MessagingException | RuntimeException e) {
			throw new DeliveryNotSentException("회원 메일 작성 실패: " + templateName, e);
		}
		sendToSmtp(toEmail, () -> mailSender.send(mimeMessage));
	}
}
