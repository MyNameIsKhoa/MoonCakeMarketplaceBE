# Google sign-in and outbound email

## Existing Identity database without Flyway history

If the Identity database already contains tables matching the original V1 schema but has no `flyway_schema_history`, set `FLYWAY_BASELINE_ON_MIGRATE=true` and `FLYWAY_BASELINE_VERSION=1` in the backend `.env`. Flyway records version 1 as the baseline and runs V2 onward. Do not use baseline version 0 for this populated database: it would run V1 `CREATE TABLE` statements against tables that already exist. For a new, empty database leave baseline-on-migrate disabled so Flyway creates the schema from V1.

## Google sign-in

1. Create OAuth client credentials in Google Cloud Console. Configure authorized origins / package identifiers for the client and use the backend-facing web client ID as the ID token audience (`serverClientId` in Flutter where applicable).
2. Set `GOOGLE_CLIENT_IDS` in the backend `.env` to the comma-separated OAuth client IDs accepted by this API. Keep these IDs out of secrets storage only when the platform treats client IDs as public; never put client secrets in the client app.
3. The client completes Google Sign-In (including any Google account 2-Step Verification) and posts the returned ID token:

```http
POST /api/auth/google
Content-Type: application/json

{"idToken":"<Google ID token>"}
```

The response is the same `AuthResponse` as `/api/auth/login`. The backend validates the signature, expiry, issuer and configured audience, and requires Google's `email_verified` claim. A first sign-in creates an ACTIVE user and customer profile. Google sign-in does not ask this API to collect Google's password or OTP.

## Email verification and password reset

Native registration creates the account as unverified and emails a verification link. The client verification page reads the `token` query parameter and posts it to `POST /api/auth/email/verify` as `{"token":"..."}`. Unverified password accounts cannot log in. Google sign-in marks the Google-verified email as verified.

To resend verification, call `POST /api/auth/email/verification-requests` with `{"email":"person@example.com"}`. It always responds `202 Accepted` regardless of whether the address needs a message.

To request a reset, call `POST /api/auth/password-reset-requests` with the same email body. It also always responds `202 Accepted`, including for unknown addresses. The reset link directs the user to `/reset-password?token=...`; the client collects a new password and posts `{"token":"...","newPassword":"..."}` to `POST /api/auth/password-resets`. A successful verification or password reset responds `204 No Content`. Tokens are random, stored only as SHA-256 hashes, expire, and are single-use. A new request invalidates the older token of the same type.

Set `APP_FRONTEND_URL` to the public origin of the frontend. Add frontend routes `/verify-email` and `/reset-password` to read the token and call the matching API endpoint.

## Real outbound email

Set `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, and optionally `MAIL_FROM` in the backend `.env`. The sample values use Gmail SMTP with STARTTLS; use a Gmail App Password (with 2-Step Verification enabled) rather than the account password. Other SMTP providers can use the same settings. `OutboundEmailService.send(recipient, subject, body)` sends a plain-text message through the configured SMTP server.

Verification, resend, and password-reset flows invoke outbound email; delivery failures are logged by the service while the API keeps generic responses. Google login itself does not require an email OTP.
