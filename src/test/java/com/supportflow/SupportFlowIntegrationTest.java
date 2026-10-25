package com.supportflow;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class SupportFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketRepository tickets;
    @Autowired UserRepository users;
    @Autowired AuditRepository audit;
    @Autowired MessageRepository messages;
    @Autowired JobUpdates jobs;
    @Autowired AiAssistant ai;
    @Autowired KnowledgeRepository knowledge;
    @Autowired NotificationRepository notifications;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    String customer,agent,admin;
    @BeforeEach void setup() throws Exception {
        notifications.deleteAll(); messages.deleteAll(); audit.deleteAll(); tickets.deleteAll();
        users.deleteAll();
        users.saveAndFlush(new AppUser("customer@example.test",passwords.encode("TestPassword123!"),Role.CUSTOMER));
        users.saveAndFlush(new AppUser("admin@example.test",passwords.encode("TestPassword123!"),Role.ADMIN));
        var a=new AppUser("agent@example.test",passwords.encode("TestPassword123!"),Role.AGENT);
        a.skills="TECHNICAL,BILLING,ACCOUNT,GENERAL"; users.saveAndFlush(a);
        customer=login("customer@example.test"); agent=login("agent@example.test"); admin=login("admin@example.test");
    }
    String login(String email) throws Exception {
        var result=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("email",email,"password","TestPassword123!"))))
            .andExpect(status().isOk()).andReturn();
        return "Bearer "+mapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }
    long create() throws Exception {
        var result=mvc.perform(post("/api/tickets").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON)
            .content("{\"subject\":\"API timeout\",\"description\":\"Our API crashes with error 500\"}"))
            .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }
    void classify(long id) { jobs.complete(id,0,new AiResult(Category.TECHNICAL,"API timeout reported","Please share the request ID.","LIVE")); }
    @Test void fullWorkflowKeepsDraftPrivateAndRequiresHumanReply() throws Exception {
        long id=create(); classify(id);
        var t=tickets.findById(id).orElseThrow(); assertThat(t.category).isEqualTo(Category.TECHNICAL); assertThat(t.assigneeId).isNotNull();
        assertThat(messages.findByTicketIdOrderByCreatedAtAsc(id)).isEmpty();
        mvc.perform(get("/api/notifications").header("Authorization",agent)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/notifications").header("Authorization",customer)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/tickets/"+id).header("Authorization",customer)).andExpect(status().isOk()).andExpect(jsonPath("$.draft").isEmpty());
        mvc.perform(get("/api/tickets/"+id).header("Authorization",agent)).andExpect(jsonPath("$.draft").isNotEmpty());
        mvc.perform(post("/api/tickets/"+id+"/messages").header("Authorization",agent).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Please share the request ID.\"}"))
            .andExpect(status().isCreated());
        assertThat(tickets.findById(id).orElseThrow().firstResponseAt).isNotNull();
        assertThat(audit.findByTicketIdOrderByCreatedAtAsc(id)).extracting(e->e.action).contains("CREATED","AI_COMPLETED","AUTO_ASSIGNED","REPLY_SENT");
    }
    @Test void accessControlAndValidation() throws Exception {
        long id=create();
        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/agents").header("Authorization",customer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"\",\"description\":\"x\"}"))
            .andExpect(status().isBadRequest());
        var r=mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"other"+id+"@test.local\",\"password\":\"OtherPass123!\"}"))
            .andExpect(status().isCreated()).andReturn();
        String other="Bearer "+mapper.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
        mvc.perform(get("/api/tickets/"+id).header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/"+id+"/messages").header("Authorization",other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets").header("Authorization",other)).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void concurrentRoutingDoesNotExceedAgentCapacity() throws Exception {
        var a=users.findByEmail("agent@example.test").orElseThrow(); a.capacity=1; users.saveAndFlush(a);
        long first=create(), second=create();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=executor.invokeAll(List.<Callable<Void>>of(()->{ classify(first); return null; },()->{classify(second); return null;}));
            for(var future:futures) future.get(10,TimeUnit.SECONDS);
        }
        assertThat(tickets.countByAssigneeIdAndStatusIn(a.id,TicketService.ACTIVE)).isEqualTo(1);
    }
    @Test void failuresRetryAndEscalationsAreIdempotent() throws Exception {
        long id=create(); jobs.fail(id,0); jobs.fail(id,1); jobs.fail(id,2); jobs.fail(id,2);
        var t=tickets.findById(id).orElseThrow(); assertThat(t.aiState).isEqualTo(AiState.FAILED); assertThat(t.attempts).isEqualTo(3);
        t.dueAt=Instant.now().minusSeconds(1); tickets.saveAndFlush(t); jobs.escalate(id); jobs.escalate(id);
        assertThat(audit.findByTicketIdOrderByCreatedAtAsc(id).stream().filter(e->e.action.equals("SLA_ESCALATED")).count()).isEqualTo(1);
        mvc.perform(post("/api/admin/tickets/"+id+"/retry-ai").header("Authorization",admin)).andExpect(status().isOk()).andExpect(jsonPath("$.aiState").value("PENDING"));
    }
    @Test void queuedTicketIsAssignedAfterCapacityIsFreed() throws Exception {
        var a=users.findByEmail("agent@example.test").orElseThrow(); a.capacity=1; users.saveAndFlush(a);
        long first=create(),second=create(); classify(first); classify(second);
        assertThat(tickets.findById(second).orElseThrow().assigneeId).isNull();
        mvc.perform(patch("/api/tickets/"+first+"/status").header("Authorization",agent).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
            .andExpect(status().isOk());
        jobs.route(second);
        assertThat(tickets.findById(second).orElseThrow().assigneeId).isEqualTo(a.id);
    }
    @Test void transitionsRejectSkippingAndCustomerMutations() throws Exception {
        long id=create(); classify(id);
        mvc.perform(patch("/api/tickets/"+id+"/status").header("Authorization",customer).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(patch("/api/tickets/"+id+"/status").header("Authorization",agent).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
            .andExpect(status().isConflict());
        mvc.perform(patch("/api/tickets/"+id+"/status").header("Authorization",agent).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))
            .andExpect(status().isOk());
        mvc.perform(patch("/api/tickets/"+id+"/status").header("Authorization",agent).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
            .andExpect(status().isOk());
    }
    @Test void invalidModelOutputIsRejectedAndSecretsAreRedacted() {
        assertThatThrownBy(()->ai.parse("{\"category\":\"HACK\",\"summary\":\"x\",\"draft\":\"x\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ai.parse("{\"category\":\"BILLING\",\"summary\":\"x\",\"draft\":\"\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AiAssistant.redact("email a@b.com password=hello Bearer abc123")).doesNotContain("a@b.com","hello","abc123");
    }
}
