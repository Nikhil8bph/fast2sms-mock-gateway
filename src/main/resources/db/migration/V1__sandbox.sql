CREATE TABLE sandbox_api_call (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    request_id VARCHAR(40) NOT NULL,
    account VARCHAR(255) NOT NULL,
    channel VARCHAR(255) NOT NULL,
    operation VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    recipients TEXT NOT NULL,
    email_from VARCHAR(255) NOT NULL,
    email_to VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(255) NOT NULL,
    attempts INT NOT NULL,
    lease VARCHAR(255),
    sent_at TIMESTAMP(6),
    failure VARCHAR(255)
);

CREATE INDEX sandbox_api_call_request ON sandbox_api_call (request_id, account, channel);
CREATE INDEX sandbox_api_call_outbox ON sandbox_api_call (status, due_at);
CREATE INDEX sandbox_api_call_history ON sandbox_api_call (account, channel, created_at);

CREATE TABLE sandbox_otp_challenge (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    code VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    issued_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    sends INT NOT NULL,
    failures INT NOT NULL,
    verified BOOLEAN NOT NULL
);
