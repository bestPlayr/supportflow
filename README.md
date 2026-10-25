# SupportFlow

A Java 21 / Spring Boot backend for AI-assisted customer support. Customers submit tickets; a persistent worker classifies them and prepares an internal draft; the router assigns an available agent with matching skills and spare capacity. Agents review drafts and send replies explicitly.

## Run locally

Requires Java 21 and Maven 3.9+.

```powershell
$env:JWT_SECRET='<your-random-secret-at-least-32-bytes>'
$env:AI_API_KEY='<your-api-key>'
$env:BOOTSTRAP_ADMIN_EMAIL='<your-admin-email>'
$env:BOOTSTRAP_ADMIN_PASSWORD='<your-admin-password>'
mvn spring-boot:run
```

The API runs at `http://localhost:8080`. Health: `GET /actuator/health`. Local data persists under `data/` using H2. Flyway manages the schema.

There are no pre-created accounts, tickets, or knowledge articles. Set the optional bootstrap credentials on the first startup to create your admin account, then remove them from the environment. The password must have at least 10 characters and fit within 72 UTF-8 bytes. Bootstrap never changes an existing account's password or grants an existing customer admin privileges. Use the admin endpoints to create agents and knowledge articles; customers sign up through `/api/auth/register`.

## PostgreSQL and Docker

```powershell
Copy-Item .env.example .env
# Fill in your database password, JWT secret, AI key, and initial admin credentials.
docker compose up --build -d
```

The API is bound to localhost; PostgreSQL is reachable only inside the Compose network. Use `docker compose down` to stop it. The named database volume retains data.

## Authentication and API

Login with a JSON body, then send `Authorization: Bearer <accessToken>`. Tokens expire after one hour. Registration always creates a CUSTOMER; only an admin can provision agents. Passwords are BCrypt hashes.

```powershell
$login = Invoke-RestMethod http://localhost:8080/api/auth/login -Method Post -ContentType application/json -Body '{"email":"<your-customer-email>","password":"<your-customer-password>"}'
$headers = @{Authorization="Bearer $($login.accessToken)"}
Invoke-RestMethod http://localhost:8080/api/tickets -Method Post -Headers $headers -ContentType application/json -Body '{"subject":"Unexpected invoice charge","description":"Please investigate invoice INV-204."}'
```

| Method | Endpoint | Purpose / access |
| --- | --- | --- |
| POST | `/api/auth/register` | Customer signup: `email`, `password` (10–72 characters, at most 72 UTF-8 bytes) |
| POST | `/api/auth/login` | Obtain JWT: `email`, `password` |
| GET | `/api/me` | Current identity |
| POST | `/api/tickets` | Customer creates: `subject`, `description` |
| GET | `/api/tickets?page=0&size=20&status=OPEN&category=TECHNICAL&q=timeout` | Filtered, paginated search; customers see their own tickets |
| GET | `/api/tickets/{id}` | Ticket detail; internal AI drafts hidden from customers |
| PATCH | `/api/tickets/{id}/status` | Assigned agent/admin: `{"status":"IN_PROGRESS"}` |
| POST | `/api/tickets/{id}/messages` | Owner, assigned agent, or admin: `{"body":"Reply text"}` |
| GET | `/api/tickets/{id}/messages` | Customer conversation / staff access |
| GET | `/api/tickets/{id}/audit` | Staff-only immutable event history |
| GET | `/api/notifications` | Current user's paginated notification inbox |
| GET | `/api/knowledge` | Staff knowledge articles |
| POST | `/api/admin/knowledge` | Create: `title`, `category`, `content` |
| GET | `/api/admin/agents` | Agent capacity, availability, skills, and workload |
| POST | `/api/admin/agents` | Create: `email`, `password`, `skills: ["TECHNICAL"]`, `capacity: 5` |
| PATCH | `/api/admin/agents/{id}` | Set: `skills`, `capacity`, `available` |
| POST | `/api/admin/tickets/{id}/assign` | Manual assignment: `{"agentId":2}` |
| POST | `/api/admin/tickets/{id}/retry-ai` | Requeue a failed AI job |

Categories: `TECHNICAL`, `BILLING`, `ACCOUNT`, `GENERAL`. Valid transitions: OPEN → IN_PROGRESS or RESOLVED; IN_PROGRESS → RESOLVED; RESOLVED → CLOSED. Replies require an active ticket. Agents can read the shared staff queue, but only the assigned agent or an admin can change a ticket or reply. Customers cannot see other customers' tickets or internal audit events.

## AI integration

AI requests use your configured provider and require `AI_API_KEY`. Set `AI_MODEL` to your chosen supported model. The application does not provide canned classifications or generated replies.

The live adapter uses a Chat Completions endpoint, configurable with `AI_URL`. Structured JSON output is validated for category, field types, and maximum lengths. Provider errors, refusals, malformed JSON, and truncated output cause retries rather than fabricated success. References: [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat) and [Spring Boot documentation](https://docs.spring.io/spring-boot/3.5/).

The prompt includes the initial ticket, the latest 20 messages, and up to 20 knowledge articles. AI processing occurs once after ticket creation, with admin retry after failure. Email addresses, bearer tokens, labelled secrets, and long digit sequences are redacted before sending to the provider. This is a basic filter, not comprehensive PII detection: use non-sensitive data. Drafts are advisory and require agent review; model citations are not independently verified.

## Background work, routing, and SLA

- Pending AI jobs live in PostgreSQL/H2 and survive restarts. A scheduler polls every five seconds. HTTP calls happen outside database transactions; committing results locks the ticket and checks its current processing state.
- Failed jobs retry after 30 and 60 seconds. Three failures move the job to FAILED, notify admins, and attempt routing to a GENERAL agent for manual review.
- Routing chooses the least-loaded available agent with an exact matching category skill. Agent-row locks serialize capacity decisions across workers. Manual admin assignment can override skill matching, but still respects availability and capacity.
- Unassigned tickets remain in a fallback queue and are revisited automatically when capacity becomes available.
- First response is due 24 hours after creation. A staff reply records the response time. Overdue active tickets are escalated once, raised to HIGH priority, audited, and admins notified. SLA tracking uses calendar hours, not business hours.
- Notifications are persisted transactionally with assignment, replies, and escalation. `GET /api/notifications` works without SMTP. Set `MAIL_ENABLED=true`, `SMTP_HOST`, `SMTP_PORT`, optional credentials, `SMTP_AUTH`, `SMTP_TLS`, and `MAIL_FROM` to enable email delivery. SMTP failures retry up to three times. Email is at-least-once: a process crash after SMTP accepts a message can cause a duplicate.

This is a modular monolith using a database-backed queue, rather than requiring RabbitMQ for local development. Multiple instances may issue duplicate AI requests, but only the first valid result is applied; provider costs are not exactly-once. Worker batches are intentionally small. For a larger deployment, add job leasing, a broker, comprehensive telemetry, rate limits, and full-text/vector knowledge retrieval.

## Tests and packaging

```powershell
mvn verify
java -jar target/supportflow-0.0.1-SNAPSHOT.jar
```

Tests cover ownership and roles, draft privacy, routing under concurrency, agent capacity, state transitions, SLA idempotency, retry exhaustion, model-output validation, redaction, and the live HTTP adapter using a local fake provider. No paid API calls are made during tests. GitHub Actions runs Maven verification on pushes and pull requests.

To run the same tests against a fresh PostgreSQL test database:

```powershell
mvn test '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/supportflow_test' '-Dspring.datasource.username=supportflow' '-Dspring.datasource.password=your-password'
```

Tests delete tickets, messages, notifications, and audit events: point them only at a disposable test database.

JWT_SECRET and AI_API_KEY are required. Configure TLS and keep secrets outside source control. The bootstrap environment variables are optional after the initial admin is created. This repository contains the backend API and has no frontend.

The V3 migration removes the original starter identities and their sample tickets, plus unchanged starter knowledge articles, from existing databases. Other users and tickets are retained.
