package com.supportflow;

import java.time.Instant;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name="app.jobs.enabled",havingValue="true",matchIfMissing=true)
class Jobs {
    private static final Logger log=LoggerFactory.getLogger(Jobs.class);
    private final TicketRepository tickets; private final KnowledgeRepository knowledge; private final MessageRepository messages;
    private final AiAssistant ai; private final JobUpdates updates;
    private long routingCursor=0;
    Jobs(TicketRepository tickets,KnowledgeRepository knowledge,MessageRepository messages,AiAssistant ai,JobUpdates updates) {
        this.tickets=tickets; this.knowledge=knowledge; this.messages=messages; this.ai=ai; this.updates=updates;
    }
    @Scheduled(fixedDelayString="${app.jobs.delay-ms:5000}",initialDelay=3000)
    void run() {
        for(var ticket:tickets.findByAiStateAndNextAttemptAtLessThanEqualOrderById(AiState.PENDING,Instant.now(),PageRequest.of(0,10))) {
            try {
                var result=ai.analyze(ticket,knowledge.findAll(),messages.findByTicketIdOrderByCreatedAtAsc(ticket.id));
                updates.complete(ticket.id,ticket.attempts,result);
            } catch(Exception e) {
                log.warn("AI processing failed for ticket {} ({})",ticket.id,e.getClass().getSimpleName());
                updates.fail(ticket.id,ticket.attempts);
            }
        }
        for(var t:tickets.findByEscalatedFalseAndFirstResponseAtIsNullAndDueAtBeforeAndStatusIn(Instant.now(),TicketService.ACTIVE,PageRequest.of(0,100))) updates.escalate(t.id);
        var waiting=tickets.findByAssigneeIdIsNullAndAiStateNotAndStatusInAndIdGreaterThanOrderById(AiState.PENDING,TicketService.ACTIVE,routingCursor,PageRequest.of(0,100));
        for(var t:waiting) { updates.route(t.id); routingCursor=t.id; }
        if(waiting.size()<100) routingCursor=0;
    }
}

@Service
class JobUpdates {
    private final TicketService service; private final TicketRepository tickets;
    JobUpdates(TicketService service,TicketRepository tickets) { this.service=service; this.tickets=tickets; }
    @Transactional
    public void complete(Long id,int expectedAttempts,AiResult result) {
        var t=service.locked(id);
        if(t.aiState!=AiState.PENDING || t.attempts!=expectedAttempts) return;
        t.category=result.category(); t.summary=result.summary(); t.draft=result.draft(); t.aiSource=result.source();
        t.aiState=AiState.COMPLETE; t.lastError=null;
        service.event(t,"system","AI_COMPLETED","Classification: "+t.category+"; provider: "+result.source());
        service.route(t);
    }
    @Transactional
    public void fail(Long id,int expectedAttempts) {
        var t=service.locked(id);
        if(t.aiState!=AiState.PENDING || t.attempts!=expectedAttempts) return;
        t.attempts++; t.lastError="AI processing failed; check provider configuration or retry later";
        t.nextAttemptAt=Instant.now().plusSeconds(30L*(1L<<t.attempts-1));
        if(t.attempts>=3) {
            t.aiState=AiState.FAILED;
            service.event(t,"system","AI_FAILED","Three attempts exhausted; manual review required");
            service.route(t);
        }
    }
    @Transactional
    public void escalate(Long id) {
            var t=service.locked(id);
            if(!t.escalated && t.firstResponseAt==null && TicketService.ACTIVE.contains(t.status) && t.dueAt.isBefore(Instant.now())) {
                t.escalated=true; t.priority=Priority.HIGH;
                service.event(t,"system","SLA_ESCALATED","First response deadline exceeded; priority raised to HIGH");
            }
    }
    @Transactional public void route(Long id) { service.route(service.locked(id)); }
}
