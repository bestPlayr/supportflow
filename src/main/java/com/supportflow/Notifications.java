package com.supportflow;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Entity @Table(name="notifications")
class Notification {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long userId;
    public Long ticketId;
    @Column(length=1000) public String body;
    public Instant createdAt=Instant.now();
    public String deliveryStatus="PENDING";
    public int attempts;
    public Instant nextAttemptAt=Instant.now();
}
interface NotificationRepository extends JpaRepository<Notification,Long> {
    Page<Notification> findByUserId(Long userId,Pageable page);
    List<Notification> findByDeliveryStatusAndNextAttemptAtLessThanEqualOrderById(String status,Instant now,Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select n from Notification n where n.id=:id")
    Notification lock(@Param("id") Long id);
}
@Service
class Notifications {
    private final NotificationRepository notifications; private final UserRepository users;
    Notifications(NotificationRepository notifications,UserRepository users) { this.notifications=notifications; this.users=users; }
    void onEvent(Ticket t,String action) {
        if(action.equals("AUTO_ASSIGNED") || action.equals("ASSIGNED")) add(t,t.assigneeId,"A ticket has been assigned to you.");
        if(action.equals("REPLY_SENT")) {
            add(t,t.customerId,"A new message was added to your ticket.");
            if(t.assigneeId!=null) add(t,t.assigneeId,"A new message was added to your assigned ticket.");
        }
        if(action.equals("SLA_ESCALATED") || action.equals("AI_FAILED")) users.findByRole(Role.ADMIN).forEach(u->add(t,u.id,action.equals("SLA_ESCALATED")?"First response SLA exceeded.":"AI processing failed; manual review required."));
    }
    private void add(Ticket t,Long user,String body) { var n=new Notification(); n.userId=user; n.ticketId=t.id; n.body="Ticket #"+t.id+": "+body; notifications.save(n); }
}
@RestController
class NotificationApi {
    private final TicketService service; private final NotificationRepository notifications;
    NotificationApi(TicketService service,NotificationRepository notifications) { this.service=service; this.notifications=notifications; }
    @GetMapping("/api/notifications") PageResponse<Notification> list(Authentication auth,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        if(page<0 || size<1 || size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination");
        return PageResponse.from(notifications.findByUserId(service.user(auth).id,PageRequest.of(page,size,Sort.by("id").descending())));
    }
}
@Component
@ConditionalOnProperty(name="app.mail.enabled",havingValue="true")
class MailJobs {
    private final NotificationRepository notifications; private final MailDelivery delivery;
    MailJobs(NotificationRepository notifications,MailDelivery delivery) { this.notifications=notifications; this.delivery=delivery; }
    @Scheduled(fixedDelay=10000,initialDelay=5000) void run() {
        for(var n:notifications.findByDeliveryStatusAndNextAttemptAtLessThanEqualOrderById("PENDING",Instant.now(),PageRequest.of(0,20))) delivery.send(n.id);
    }
}
@Service
class MailDelivery {
    private final NotificationRepository notifications; private final UserRepository users; private final JavaMailSender mail; private final String from;
    MailDelivery(NotificationRepository notifications,UserRepository users,JavaMailSender mail,@Value("${app.mail.from:support@supportflow.local}") String from) {
        this.notifications=notifications; this.users=users; this.mail=mail; this.from=from;
    }
    @Transactional public void send(Long id) {
        var n=notifications.lock(id);
        if(n==null || !n.deliveryStatus.equals("PENDING") || n.nextAttemptAt.isAfter(Instant.now())) return;
        var message=new SimpleMailMessage(); message.setFrom(from); message.setTo(users.findById(n.userId).orElseThrow().email);
        message.setSubject("SupportFlow ticket #"+n.ticketId); message.setText(n.body);
        try { mail.send(message); n.deliveryStatus="SENT"; }
        catch(MailException e) { n.attempts++; n.nextAttemptAt=Instant.now().plusSeconds(60L*n.attempts); if(n.attempts>=3) n.deliveryStatus="FAILED"; }
    }
}
