-- Remove only the known starter identities and their sample tickets.
delete from notifications where user_id in (select id from app_users where email in ('admin@supportflow.local','agent@supportflow.local','customer@supportflow.local'))
 or ticket_id in (select id from tickets where customer_id in (select id from app_users where email='customer@supportflow.local'));
delete from messages where author_id in (select id from app_users where email in ('admin@supportflow.local','agent@supportflow.local','customer@supportflow.local'))
 or ticket_id in (select id from tickets where customer_id in (select id from app_users where email='customer@supportflow.local'));
delete from audit_events where ticket_id in (select id from tickets where customer_id in (select id from app_users where email='customer@supportflow.local'));
delete from tickets where customer_id in (select id from app_users where email='customer@supportflow.local');
update tickets set assignee_id=null where assignee_id in (select id from app_users where email in ('admin@supportflow.local','agent@supportflow.local','customer@supportflow.local'));
delete from app_users where email in ('admin@supportflow.local','agent@supportflow.local','customer@supportflow.local');
delete from knowledge_articles where
 (title='Troubleshooting API errors' and content='Collect the error code, timestamp, and request ID. Retry once after checking connectivity. If the issue persists, share those details with support; omit tokens and personal data.') or
 (title='Unexpected charges' and content='Check the invoice number and transaction date. Share the invoice reference with support so an agent can investigate. Do not send full card numbers. Refund eligibility must be reviewed by an agent.') or
 (title='Account access' and content='Use the password reset option on the sign-in page. If the reset message does not arrive, check the spam folder. Never share your password or verification code with support.');
