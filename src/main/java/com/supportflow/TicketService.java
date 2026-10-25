package com.supportflow;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
class TicketService {
    static final List<Status> ACTIVE=List.of(Status.OPEN,Status.IN_PROGRESS);
    private final TicketRepository tickets; private final UserRepository users;
    private final MessageRepository messages; private final AuditRepository audit;
    private final Notifications notifications;
    TicketService(TicketRepository tickets,UserRepository users,MessageRepository messages,AuditRepository audit,Notifications notifications) {
        this.tickets=tickets; this.users=users; this.messages=messages; this.audit=audit; this.notifications=notifications;
    }
    AppUser user(Authentication auth) { return users.findByEmail(auth.getName()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED)); }
    void staff(AppUser u) { if(u.role==Role.CUSTOMER) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Staff access required"); }
    Ticket create(TicketApi.CreateTicket request,AppUser u) {
        if(u.role!=Role.CUSTOMER) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Customer access required");
        var t=new Ticket(); t.subject=request.subject(); t.description=request.description(); t.customerId=u.id;
        tickets.save(t); event(t,u.email,"CREATED","Ticket queued for classification"); return t;
    }
    Page<Ticket> list(AppUser u,Status status,Category category,String q,Pageable page) {
        return tickets.search(u.role==Role.CUSTOMER?u.id:null,status,category,q,page).map(t->visible(t,u));
    }
    Ticket get(Long id,AppUser u) { var t=find(id); access(t,u); return visible(t,u); }
    private Ticket visible(Ticket t,AppUser u) {
        // Do not expose internal AI drafts or processing errors to customers.
        if(u.role!=Role.CUSTOMER) return t;
        var v=new Ticket(); v.id=t.id; v.version=t.version; v.subject=t.subject; v.description=t.description;
        v.customerId=t.customerId; v.assigneeId=t.assigneeId; v.category=t.category; v.status=t.status;
        v.priority=t.priority; v.createdAt=t.createdAt; v.dueAt=t.dueAt; v.firstResponseAt=t.firstResponseAt;
        v.escalated=t.escalated; v.aiState=t.aiState; v.attempts=t.attempts; v.nextAttemptAt=t.nextAttemptAt;
        return v;
    }
    Ticket status(Long id,Status next,AppUser u) {
        var t=locked(id); editable(t,u);
        boolean valid=switch(t.status) {
            case OPEN -> next==Status.IN_PROGRESS || next==Status.RESOLVED;
            case IN_PROGRESS -> next==Status.RESOLVED;
            case RESOLVED -> next==Status.CLOSED;
            case CLOSED -> false;
        };
        if(!valid) throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid status transition");
        var old=t.status; t.status=next; event(t,u.email,"STATUS_CHANGED",old+" -> "+next); return t;
    }
    Message reply(Long id,String body,AppUser u) {
        var t=locked(id); access(t,u);
        if(u.role!=Role.CUSTOMER) editable(t,u);
        if(t.status==Status.CLOSED || t.status==Status.RESOLVED) throw new ResponseStatusException(HttpStatus.CONFLICT,"Ticket no longer accepts replies");
        var m=new Message(); m.ticketId=id; m.authorId=u.id; m.body=body; messages.save(m);
        if(u.role!=Role.CUSTOMER && t.firstResponseAt==null) t.firstResponseAt=Instant.now();
        event(t,u.email,"REPLY_SENT","Message "+m.id); return m;
    }
    List<Message> messages(Long id,AppUser u) { access(find(id),u); return messages.findByTicketIdOrderByCreatedAtAsc(id); }
    List<AuditEvent> history(Long id,AppUser u) { staff(u); access(find(id),u); return audit.findByTicketIdOrderByCreatedAtAsc(id); }
    Ticket assign(Long id,Long agentId,AppUser u) {
        var t=locked(id); requireActive(t);
        var agent=users.lockAgents().stream().filter(a->a.id.equals(agentId)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found"));
        if(!agent.available || (!agent.id.equals(t.assigneeId) && workload(agent.id)>=agent.capacity)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Agent unavailable or at capacity");
        t.assigneeId=agentId; event(t,u.email,"ASSIGNED","Agent "+agentId); return t;
    }
    Ticket retry(Long id,AppUser u) {
        var t=locked(id); requireActive(t);
        if(t.aiState!=AiState.FAILED) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed jobs can be retried");
        t.aiState=AiState.PENDING; t.attempts=0; t.lastError=null; t.nextAttemptAt=Instant.now();
        event(t,u.email,"AI_RETRIED","Job requeued"); return t;
    }
    void route(Ticket t) {
        if(t.assigneeId!=null || !ACTIVE.contains(t.status)) return;
        var agents=users.lockAgents();
        AppUser best=null; long lowest=Long.MAX_VALUE;
        for(var a:agents) {
            if(!a.available || !Arrays.asList(a.skills.split(",")).contains(t.category.name())) continue;
            long load=workload(a.id);
            if(load<a.capacity && load<lowest) { best=a; lowest=load; }
        }
        if(best!=null) { t.assigneeId=best.id; event(t,"system","AUTO_ASSIGNED","Agent "+best.id); }
    }
    long workload(Long id) { return tickets.countByAssigneeIdAndStatusIn(id,ACTIVE); }
    Ticket locked(Long id) { return tickets.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Ticket not found")); }
    private Ticket find(Long id) { return tickets.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Ticket not found")); }
    private void requireActive(Ticket t) { if(!ACTIVE.contains(t.status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Ticket is inactive"); }
    private void access(Ticket t,AppUser u) { if(u.role==Role.CUSTOMER && !t.customerId.equals(u.id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Ticket not found"); }
    private void editable(Ticket t,AppUser u) { staff(u); if(u.role==Role.AGENT && !u.id.equals(t.assigneeId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only the assigned agent may modify this ticket"); }
    void event(Ticket t,String actor,String action,String detail) { var e=new AuditEvent(); e.ticketId=t.id; e.actor=actor; e.action=action; e.detail=detail; audit.save(e); notifications.onEvent(t,action); }
}
