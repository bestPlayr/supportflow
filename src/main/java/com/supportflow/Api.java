package com.supportflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

record PageResponse<T>(List<T> content,int page,int size,long totalElements,int totalPages) {
    static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(result.getContent(),result.getNumber(),result.getSize(),result.getTotalElements(),result.getTotalPages());
    }
}

@RestController @RequestMapping("/api/auth")
class AuthApi {
    record Credentials(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(min=10,max=72) String password) {}
    record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
    private final UserRepository users; private final PasswordEncoder passwords; private final Tokens tokens;
    AuthApi(UserRepository users, PasswordEncoder passwords, Tokens tokens) { this.users=users; this.passwords=passwords; this.tokens=tokens; }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    TokenResponse register(@Valid @RequestBody Credentials request) {
        if(request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must fit within 72 UTF-8 bytes");
        var user=new AppUser(request.email().toLowerCase(Locale.ROOT), passwords.encode(request.password()), Role.CUSTOMER);
        users.saveAndFlush(user);
        return new TokenResponse(tokens.issue(user), "Bearer", 3600);
    }
    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody Credentials request) {
        var user=users.findByEmail(request.email().toLowerCase(Locale.ROOT)).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials"));
        if(!passwords.matches(request.password(),user.password)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        return new TokenResponse(tokens.issue(user),"Bearer",3600);
    }
}

@RestController @RequestMapping("/api")
class TicketApi {
    record CreateTicket(@NotBlank @Size(max=200) String subject, @NotBlank @Size(max=10000) String description) {}
    record StatusChange(@NotNull Status status) {}
    record Reply(@NotBlank @Size(max=4000) String body) {}
    record Assignment(@NotNull Long agentId) {}
    record AgentRequest(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(min=10,max=72) String password,
                        @NotEmpty Set<@NotNull Category> skills, @Min(1) @Max(100) int capacity) {}
    record AgentUpdate(@NotEmpty Set<@NotNull Category> skills, @Min(1) @Max(100) int capacity, boolean available) {}
    record AgentView(Long id, String email, String skills, int capacity, boolean available, long workload) {}
    record ArticleRequest(@NotBlank @Size(max=200) String title, @NotNull Category category, @NotBlank @Size(max=4000) String content) {}
    private final TicketService service; private final UserRepository users; private final KnowledgeRepository knowledge; private final PasswordEncoder passwords;
    TicketApi(TicketService service, UserRepository users, KnowledgeRepository knowledge, PasswordEncoder passwords) {
        this.service=service; this.users=users; this.knowledge=knowledge; this.passwords=passwords;
    }
    @GetMapping("/me") Map<String,Object> me(Authentication auth) { var u=service.user(auth); return Map.of("id",u.id,"email",u.email,"role",u.role); }
    @PostMapping("/tickets") @ResponseStatus(HttpStatus.CREATED)
    Ticket create(@Valid @RequestBody CreateTicket request, Authentication auth) { return service.create(request,service.user(auth)); }
    @GetMapping("/tickets")
    PageResponse<Ticket> list(Authentication auth, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
                      @RequestParam(required=false) Status status, @RequestParam(required=false) Category category,
                      @RequestParam(defaultValue="") String q) {
        if(page<0 || size<1 || size>100 || q.length()>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination or query");
        return PageResponse.from(service.list(service.user(auth),status,category,q,PageRequest.of(page,size,Sort.by("id").descending())));
    }
    @GetMapping("/tickets/{id}") Ticket get(@PathVariable Long id, Authentication auth) { return service.get(id,service.user(auth)); }
    @PatchMapping("/tickets/{id}/status") Ticket status(@PathVariable Long id,@Valid @RequestBody StatusChange request,Authentication auth) { return service.status(id,request.status(),service.user(auth)); }
    @PostMapping("/tickets/{id}/messages") @ResponseStatus(HttpStatus.CREATED)
    Message reply(@PathVariable Long id,@Valid @RequestBody Reply request,Authentication auth) { return service.reply(id,request.body(),service.user(auth)); }
    @GetMapping("/tickets/{id}/messages") List<Message> messages(@PathVariable Long id,Authentication auth) { return service.messages(id,service.user(auth)); }
    @GetMapping("/tickets/{id}/audit") List<AuditEvent> audit(@PathVariable Long id,Authentication auth) { return service.history(id,service.user(auth)); }
    @PostMapping("/admin/tickets/{id}/assign") Ticket assign(@PathVariable Long id,@Valid @RequestBody Assignment request,Authentication auth) { return service.assign(id,request.agentId(),service.user(auth)); }
    @PostMapping("/admin/tickets/{id}/retry-ai") Ticket retry(@PathVariable Long id,Authentication auth) { return service.retry(id,service.user(auth)); }
    @GetMapping("/admin/agents") List<AgentView> agents() { return users.findAll().stream().filter(u->u.role==Role.AGENT).map(this::view).toList(); }
    @PostMapping("/admin/agents") @ResponseStatus(HttpStatus.CREATED)
    AgentView agent(@Valid @RequestBody AgentRequest request) {
        if(request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must fit within 72 UTF-8 bytes");
        var u=new AppUser(request.email().toLowerCase(Locale.ROOT),passwords.encode(request.password()),Role.AGENT);
        u.skills=skills(request.skills()); u.capacity=request.capacity(); return view(users.saveAndFlush(u));
    }
    @PatchMapping("/admin/agents/{id}") @Transactional
    AgentView updateAgent(@PathVariable Long id,@Valid @RequestBody AgentUpdate request) {
        var agents=users.lockAgents();
        var u=agents.stream().filter(a->a.id.equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found"));
        u.skills=skills(request.skills()); u.capacity=request.capacity(); u.available=request.available(); return view(u);
    }
    @GetMapping("/knowledge") List<KnowledgeArticle> knowledge(Authentication auth) { service.staff(service.user(auth)); return knowledge.findAll(); }
    @PostMapping("/admin/knowledge") @ResponseStatus(HttpStatus.CREATED)
    KnowledgeArticle article(@Valid @RequestBody ArticleRequest request) { var a=new KnowledgeArticle(); a.title=request.title(); a.category=request.category(); a.content=request.content(); return knowledge.save(a); }
    private String skills(Set<Category> skills) { return String.join(",",skills.stream().map(Enum::name).sorted().toList()); }
    private AgentView view(AppUser u) { return new AgentView(u.id,u.email,u.skills,u.capacity,u.available,service.workload(u.id)); }
}
