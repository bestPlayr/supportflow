package com.supportflow;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<AppUser,Long> {
    Optional<AppUser> findByEmail(String email);
    List<AppUser> findByRole(Role role);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.role = com.supportflow.Role.AGENT order by u.id")
    List<AppUser> lockAgents();
}
interface TicketRepository extends JpaRepository<Ticket,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> lockById(@Param("id") Long id);
    @Query("select t from Ticket t where (:customer is null or t.customerId = :customer) and (:status is null or t.status = :status) and (:category is null or t.category = :category) and (:q = '' or lower(t.subject) like lower(concat('%', :q, '%')))")
    Page<Ticket> search(@Param("customer") Long customer, @Param("status") Status status, @Param("category") Category category, @Param("q") String q, Pageable page);
    long countByAssigneeIdAndStatusIn(Long assignee, Collection<Status> statuses);
    List<Ticket> findByAiStateAndNextAttemptAtLessThanEqualOrderById(AiState state, Instant now, Pageable page);
    List<Ticket> findByEscalatedFalseAndFirstResponseAtIsNullAndDueAtBeforeAndStatusIn(Instant now, Collection<Status> statuses, Pageable page);
    List<Ticket> findByAssigneeIdIsNullAndAiStateNotAndStatusInAndIdGreaterThanOrderById(AiState state, Collection<Status> statuses, Long after, Pageable page);
}
interface MessageRepository extends JpaRepository<Message,Long> {
    List<Message> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
}
interface AuditRepository extends JpaRepository<AuditEvent,Long> {
    List<AuditEvent> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
}
interface KnowledgeRepository extends JpaRepository<KnowledgeArticle,Long> {
    List<KnowledgeArticle> findByCategory(Category category);
}
