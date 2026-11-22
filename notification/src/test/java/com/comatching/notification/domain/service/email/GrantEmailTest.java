package com.comatching.notification.domain.service.email;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.notification.domain.delivery.DeliveryNotSentException;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GrantEmailTest {
    private static final String RECIPIENT = "recipient@example.invalid";

    static Stream<SendFailedException> explicitRejections() throws Exception {
        Address[] recipient = {new InternetAddress(RECIPIENT)};
        return Stream.of(
                new SendFailedException("invalid recipient", null, null, null, recipient),
                new SMTPSendFailedException(".", 451, "451 temporary failure", null, null, recipient, null));
    }

    @ParameterizedTest
    @MethodSource("explicitRejections")
    void explicitRejectionIsSafeToReplay(SendFailedException rejection) {
        JavaMailSender smtp = mock();
        doAnswer(call -> {
            throw new MailSendException(Map.of(call.getArgument(0), rejection));
        }).when(smtp).send(any(SimpleMailMessage.class));
        var service = new EmailServiceImpl(smtp, mock(SpringTemplateEngine.class));
        var event = new ItemsGranted("items-granted:1", 1L, 1L, LocalDateTime.now(),
                List.of(new ItemsGranted.GrantedItem(ItemType.OPTION_TICKET, 1)));
        assertThatThrownBy(() -> service.sendItemsGranted(RECIPIENT, event))
                .isInstanceOf(DeliveryNotSentException.class);
    }
}
