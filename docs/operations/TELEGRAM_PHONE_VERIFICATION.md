# Telegram Phone Verification — Setup

Norfold verifies a user's phone number through the Telegram bot
**@Norfold_auth_bot** using Telegram's `request_contact` flow. No SMS, no
Telegram OIDC. The Android app never talks to Telegram directly — it only calls
Supabase, and Supabase talks to Telegram.

```
Norfold app ──► Supabase Edge Functions ──► Telegram Bot API
                        ▲
Telegram  ──────────────┘  (webhook: Telegram → Supabase → Postgres)
```

## Pieces

| Piece | What it is |
|---|---|
| `supabase/migrations/202607250001_telegram_verification.sql` | `account_phone_identities`, `telegram_verification_sessions`, `telegram_processed_updates` + the two RPCs. |
| `supabase/functions/telegram-verification-start` | Auth'd user starts a session, gets the `t.me` link. JWT **on**. |
| `supabase/functions/telegram-auth-webhook` | Receives Telegram updates. JWT **off**; authenticates via `X-Telegram-Bot-Api-Secret-Token`. |
| Android `NorfoldSupabase.startTelegramVerification()` / `telegramVerificationStatus()` / `verifiedPhoneE164()` | Client entry points. |
| Deep link `norfold://verify/telegram` | "Return to Norfold" button target. Carries **no trust** — the app re-queries Supabase for the real status. |

## Secrets (Supabase → Edge Functions → Secrets)

Set these two. **Never** put them in the app, the repo, or a URL.

| Secret | Value |
|---|---|
| `TELEGRAM_AUTH_BOT_TOKEN` | The BotFather token for @Norfold_auth_bot. |
| `TELEGRAM_WEBHOOK_SECRET` | A random 32-byte hex string you generate. |

Generate the webhook secret (PowerShell):

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToHexString($bytes)
```

Paste that same value into `TELEGRAM_WEBHOOK_SECRET`.

## Deploy order

```bash
supabase db push
supabase functions deploy telegram-verification-start
supabase functions deploy telegram-auth-webhook
```

The webhook URL will be:

```
https://<PROJECT_REF>.supabase.co/functions/v1/telegram-auth-webhook
```

## Register the webhook (do this LAST, after deploy)

Run `scripts/local/register-telegram-webhook.ps1` (gitignored; prompts you for
the token/secret/URL — nothing is stored). It calls `setWebhook` with
`secret_token`, `allowed_updates=["message"]`, `drop_pending_updates=true`, then
prints `getWebhookInfo` so you can confirm `pending_update_count = 0` and no
`last_error_message`.

## BotFather settings

- `/setprivacy` → **Enable**
- `/setjoingroups` → **Disable**
- inline mode → leave **off**
- `/setcommands` →
  ```
  start - Verify your phone number for Norfold
  ```
- `/setdescription` and `/setabouttext` → short verification-only text.

## Security properties (by design)

- `/start` token: 32 random bytes, single-use, 10-min expiry, **SHA-256 hashed
  at rest** (raw token never stored).
- Webhook authenticated by constant-time compare of `X-Telegram-Bot-Api-Secret-Token`.
- Contact ownership enforced: `contact.user_id == message.from.id` — forwarded or
  typed contacts are rejected.
- Idempotent: every `update_id` is recorded once; Telegram retries are no-ops.
- Atomic completion in one transaction: phone uniqueness + provider-subject
  uniqueness + expiry, with the session row locked. A phone already linked to
  another account returns `PHONE_ALREADY_LINKED` — never silently reassigned.
- Clients can read only their own session/phone; they can never mark themselves
  verified. All verified writes are service-role only.
- The return deep link is never treated as proof of success; the app always
  re-reads the session status from Supabase.
