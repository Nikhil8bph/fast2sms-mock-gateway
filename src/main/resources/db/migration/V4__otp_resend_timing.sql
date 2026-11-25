ALTER TABLE sandbox_otp_challenge ADD COLUMN last_sent_at TIMESTAMP(6);

UPDATE sandbox_otp_challenge AS challenge
SET last_sent_at = COALESCE((
    SELECT MAX(api_call.created_at)
    FROM sandbox_api_call AS api_call
    WHERE api_call.account = SUBSTRING(challenge.id, 1, 64)
      AND api_call.channel = 'OTP'
      AND api_call.recipients = SUBSTRING(challenge.id, 66)
      AND api_call.created_at >= challenge.issued_at
), challenge.issued_at);

ALTER TABLE sandbox_otp_challenge MODIFY COLUMN last_sent_at TIMESTAMP(6) NOT NULL;
