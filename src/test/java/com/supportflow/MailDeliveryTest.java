package com.supportflow;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailDeliveryTest {
    final NotificationRepository notifications=mock(NotificationRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final JavaMailSender sender=mock(JavaMailSender.class);
    final MailDelivery delivery=new MailDelivery(notifications,users,sender,"support@test.local");
    Notification notification() {
        var n=new Notification(); n.id=1L; n.userId=2L; n.ticketId=3L; n.body="Ticket assigned";
        when(notifications.lock(1L)).thenReturn(n);
        when(users.findById(2L)).thenReturn(Optional.of(new AppUser("agent@test.local","unused",Role.AGENT)));
        return n;
    }
    @Test void successfulMailIsNotSentTwice() {
        var n=notification(); delivery.send(1L); delivery.send(1L);
        assertThat(n.deliveryStatus).isEqualTo("SENT"); verify(sender,times(1)).send(any(SimpleMailMessage.class));
    }
    @Test void exhaustedMailRetriesAreMarkedFailed() {
        var n=notification(); n.attempts=2;
        doThrow(new MailSendException("SMTP unavailable")).when(sender).send(any(SimpleMailMessage.class));
        delivery.send(1L);
        assertThat(n.deliveryStatus).isEqualTo("FAILED"); assertThat(n.attempts).isEqualTo(3);
        delivery.send(1L); verify(sender,times(1)).send(any(SimpleMailMessage.class));
    }
}
