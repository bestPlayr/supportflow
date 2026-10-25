package com.supportflow;

import jakarta.persistence.*;
import java.time.Instant;

enum Role { CUSTOMER, AGENT, ADMIN }
enum Category { TECHNICAL, BILLING, ACCOUNT, GENERAL }
enum Status { OPEN, IN_PROGRESS, RESOLVED, CLOSED }
enum Priority { LOW, NORMAL, HIGH, URGENT }
enum AiState { PENDING, COMPLETE, FAILED }

@Entity @Table(name="app_users")
class AppUser {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false, unique=true) public String email;
    @Column(nullable=false) public String password;
    @Enumerated(EnumType.STRING) public Role role;
    public String skills = "GENERAL";
    public int capacity = 5;
    public boolean available = true;
    protected AppUser() {}
    AppUser(String email, String password, Role role) { this.email=email; this.password=password; this.role=role; }
}

@Entity @Table(name="tickets")
class Ticket {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Version public long version;
    public String subject;
    @Column(length=10000) public String description;
    public Long customerId;
    public Long assigneeId;
    @Enumerated(EnumType.STRING) public Category category = Category.GENERAL;
    @Enumerated(EnumType.STRING) public Status status = Status.OPEN;
    @Enumerated(EnumType.STRING) public Priority priority = Priority.NORMAL;
    public Instant createdAt = Instant.now();
    public Instant dueAt = createdAt.plusSeconds(86400);
    public Instant firstResponseAt;
    public boolean escalated;
    @Enumerated(EnumType.STRING) public AiState aiState = AiState.PENDING;
    public int attempts;
    public Instant nextAttemptAt = Instant.now();
    @Column(length=2000) public String summary;
    @Column(length=4000) public String draft;
    public String aiSource;
    public String lastError;
}

@Entity @Table(name="messages")
class Message {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long ticketId;
    public Long authorId;
    @Column(length=4000) public String body;
    public Instant createdAt = Instant.now();
}

@Entity @Table(name="audit_events")
class AuditEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long ticketId;
    public String actor;
    public String action;
    @Column(length=1000) public String detail;
    public Instant createdAt = Instant.now();
}

@Entity @Table(name="knowledge_articles")
class KnowledgeArticle {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public String title;
    @Enumerated(EnumType.STRING) public Category category;
    @Column(length=4000) public String content;
}
