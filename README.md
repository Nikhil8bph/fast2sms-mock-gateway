# Fast2SMS sandbox

**For local development and testing only.** FastAPI support and replacement of FastAPI or the official Fast2SMS service are outside this project's scope. Fast2SMS references describe the HTTP behavior simulated by this independent Spring Boot sandbox.

For development that requires live delivery and for production, use the official Fast2SMS service. [Fast2SMS website](https://www.fast2sms.com/) · [Fast2SMS API reference](https://docs.fast2sms.com/reference/)

A Spring Boot sandbox for the SMS, OTP, WhatsApp, RCS, Voice, Wallet and Webhook APIs in the Fast2SMS reference. It saves accepted requests, message payloads, OTP state, mutable account data and email delivery attempts in MySQL. Each accepted API operation queues a plain-text email to the configured inbox. Scheduled sends wait until their requested time, and an SMS batch with different times gets one email per delivery time.

## Configuration

Fill in the local `.env` using [`.env.example`](.env.example). The app loads it from the working directory, and process environment variables take precedence. Enter values without surrounding quotes when using the same file with Docker. Keep `.env` out of source control.

| Variable | Value |
| --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | MySQL host, port and schema from your Aiven service |
| `DB_USERNAME`, `DB_PASSWORD` | MySQL credentials with permission to run Flyway migrations |
| `DB_SSL_MODE` | Connector/J SSL mode, defaults to `REQUIRED` |
| `EMAIL_FROM` | Gmail account used both as the sender and SMTP username |
| `EMAIL_TO` | Single destination inbox for every sandbox email |
| `GMAIL_APP_PASSWORD` | App password for the sender account, with grouping spaces removed |
| `FAST2SMS_API_KEYS` | One or more private sandbox keys, comma-separated |
| `PORT` | Optional HTTP port, defaults to 8080 from source and 10000 in Docker |
| `MOCK_WALLET_BALANCE` | Optional fixed wallet response, defaults to 1000.00 |
| `DLT_EXCEL_ORIGIN` | Optional trusted HTTPS origin for fetching DLT export URLs, such as `https://fixtures.example.test` |
| `OTP_VERIFY_ATTEMPTS` | Smart OTP verification attempts per code, defaults to 10 |
| `OTP_RESEND_WAIT_SECONDS` | Time between Smart OTP sends for the same number and template, defaults to 10 seconds. Use 0 for tests that need immediate resends |

Configure Gmail with [2-Step Verification and an app password](https://support.google.com/mail/answer/185833). The service uses SMTP STARTTLS on port 587. Both email addresses, the Gmail app password, and at least one API key are required at startup.

Use the raw key in the `Authorization` header, as in the [Fast2SMS authorization reference](https://docs.fast2sms.com/reference/authorization). Each key has its own history and resources. Changing a key creates a new sandbox account for subsequent calls. Use test phone numbers, messages and OTPs because payloads are stored in the database and may appear in the configured mailbox.

## Run locally

Use Java 21 and Maven, or the Maven wrapper.

```sh
mvn test
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.address=127.0.0.1"
```

With Docker from the repository root:

```sh
docker build -t fast2sms-mock-gateway .
docker run --rm --env-file .env -p 127.0.0.1:8080:10000 fast2sms-mock-gateway
```

Use `http://localhost:8080` as your client's sandbox base URL. The health endpoint is `/health`.

```sh
curl -G 'http://localhost:8080/dev/bulkV2' \
  -H "Authorization: $SANDBOX_KEY" \
  --data-urlencode 'route=q' \
  --data-urlencode 'numbers=9999999999' \
  --data-urlencode 'message=Hello from the sandbox'
```

```sh
curl 'http://localhost:8080/dev/whatsapp' \
  -H "Authorization: $SANDBOX_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"message_id":20005,"phone_number_id":"100000000000002","numbers":"9999999999"}'
```

Set `SANDBOX_KEY` in your shell to one configured key before running these examples. On PowerShell, use `curl.exe` and `$env:SANDBOX_KEY`.

## Swagger and OpenAPI

Open `/swagger-ui.html` on the running sandbox. Select **Authorize**, paste one value from `FAST2SMS_API_KEYS`, and use **Try it out** with test data. Successful sends can queue real email to `EMAIL_TO`.

The running service publishes OpenAPI at `/v3/api-docs` (JSON) and `/v3/api-docs.yaml` (YAML). The [contract source](src/main/resources/openapi/fast2sms-sandbox.yaml) includes request and response schemas, sample payloads, upload fields, and the sandbox error envelopes.

## API coverage

| Feature | Paths under `/dev` |
| --- | --- |
| SMS sends | GET/POST `/bulkV2` for Quick SMS, DLT, DLT Manual and classic OTP. POST `/custom` for Quick, DLT and DLT Manual batches |
| Smart OTP | GET/POST `/otp/send`, `/otp/verify`, `/otp/resend` |
| WhatsApp sends | GET/POST `/whatsapp`, POST `/whatsapp-session`, `/whatsapp-direct-send`, `/whatsapp/{version}/{phone_number_id}/messages` |
| Delivery and analytics | GET `/dlr/{request_id}`, `/whatsapp/{request_id}`, `/sms_logs`, `/sms_summary`, `/whatsapp_logs`, `/whatsapp_summary` |
| DLT and wallet | `/dlt_manager` and its entity, sender, template and Excel import paths, POST `/wallet` |
| WhatsApp management | `/dlt_manager/whatsapp` plus versioned WABA and phone lookup, business profiles, templates, media uploads and handles, QR records and block lists |
| RCS | GET/POST `/rcs` for plain, rich and media sends. Carousels use POST with JSON |
| Voice | POST `/voice/bulk` for simple, key-press and call-patch campaigns. GET `/voice/files` and `/voice/report` |
| Webhook configuration | GET/POST `/webhook/v2/{sms,otp,whatsapp,rcs,voice}`, PUT/DELETE `/webhook/v2/{sms,otp,whatsapp,rcs,voice}/{id}` |

The sandbox supplies WABA ID `100000000000001`, phone number ID `100000000000002`, and simple template message ID `20005`. Look them up with `GET /dev/dlt_manager/whatsapp?type=template`. You can create additional templates and retrieve them through the versioned API. Send payloads are included in the email, including template IDs and variables.

The route tests cover 68 HTTP method/path combinations from the [Fast2SMS reference index](https://docs.fast2sms.com/llms.txt): every documented operation, with the five webhook services counted once per service. Where the reference shows multiple schemas on a shared route, use the documented body for your type, such as an RCS carousel or a Meta-format WhatsApp template.

The email queue retries failed sends up to five attempts. Delivery and voice reports use sandbox email outcomes. Sent means Gmail SMTP accepted the email. A service interruption after SMTP accepts an email can result in a duplicate on retry. The sandbox uses a fixed wallet balance, locally approved templates, sample voice files and simulated carrier outcomes. Webhook configurations persist locally. QR responses include an authenticated image URL for the requested PNG or SVG, and the QR links to a local record.

DLT Excel endpoints accept the documented hosted URL fields. Set `DLT_EXCEL_ORIGIN` to fetch exports from a trusted HTTPS origin. By default the sandbox records those URLs. Both endpoints also accept a multipart form with `entity_id` and `file` for local XLSX or UTF-8 CSV fixtures. The parser imports recognized approved rows and stores the source file. Imported DLT templates render their variable values in sandbox email. A single DLT Manual send can infer omitted IDs from a unique matching saved template. Batch calls require both IDs for every item.

Smart OTP resends reuse the previous code and restart its configured expiry. Each code can be sent up to five times, including the first send.

The Direct Send route uses sandbox fields `phone_number_id`, `category=utility`, `numbers` and `text` based on the fields Fast2SMS names in its [Direct Send guide](https://www.fast2sms.com/help/whatsapp-direct-send-utility-messages/).

## License

Use is limited to local development and testing under the [Local Development License](LICENSE).
