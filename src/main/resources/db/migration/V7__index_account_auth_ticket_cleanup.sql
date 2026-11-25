ALTER TABLE auth_action_tickets
  ADD KEY idx_auth_ticket_social_subject (provider, provider_user_id, purpose),
  ADD KEY idx_auth_ticket_email_purpose (email, purpose);
