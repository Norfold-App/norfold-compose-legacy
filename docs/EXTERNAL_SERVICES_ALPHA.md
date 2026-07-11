# Norfold Alpha External Services

This runbook provisions a fresh, tester-only Alpha environment. Do not reuse the pre-rebrand Google project or place a provider secret, private key, database password, Stripe secret, or service-account JSON in the Android project.

## Fixed Identity

| Item | Value |
| --- | --- |
| Android package | `com.norfold.app` |
| Auth callback | `norfold://auth/callback` |
| Calendar callback | `norfold://integrations/calendar` |
| Supabase callback | `https://<SUPABASE_REF>.supabase.co/auth/v1/callback` |
| Calendar Edge callback | `https://<SUPABASE_REF>.supabase.co/functions/v1/calendar-callback` |
| Homepage | `https://sheikhti1205.github.io/Norfold/` |
| Privacy | `https://sheikhti1205.github.io/Norfold/privacy.html` |
| Terms | `https://sheikhti1205.github.io/Norfold/terms.html` |
| Debug SHA-1 | `87:AD:96:65:0C:40:A5:E4:68:5A:02:DA:5E:1A:13:00:53:F9:F8:DB` |
| Debug SHA-256 | `E3:44:65:D3:86:FE:0E:34:8B:91:36:36:D8:A9:B4:71:A1:85:9E:EA:13:7E:CF:8B:91:8F:24:BD:91:AE:55:BF` |

## Secret Boundaries

Android may contain only the Supabase URL, Supabase publishable key, Web OAuth client ID, project IDs, callback URLs, and `google-services.json`. Everything else belongs in Supabase Function secrets, the provider console, or CI secret storage.

Configure public Android values by copying `norfold.properties.example` to the gitignored `norfold.properties`:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_REPLACE_ME
GOOGLE_SERVER_CLIENT_ID=REPLACE_ME.apps.googleusercontent.com
GOOGLE_CLOUD_PROJECT_ID=norfold-alpha
FIREBASE_PROJECT_ID=norfold-alpha
```

Never add client secrets to this file. The app disables cloud capabilities when required public values are absent.

## 1. Supabase Alpha

### Create and link

1. Create a project named **Norfold Alpha** in the Singapore region.
2. Record the project reference, project URL, publishable key, and database password in a password manager. Do not copy the service-role key into Android.
3. Install/login to the CLI and link this repository:

```bash
npx supabase login
npx supabase link --project-ref <SUPABASE_REF>
npx supabase db reset
npx supabase db push
```

4. Confirm the migration in `supabase/migrations` completes locally and remotely.
5. In Authentication > URL Configuration, set Site URL and an exact additional redirect URL to `norfold://auth/callback`.
6. Keep anonymous and email authentication disabled. Enable Google, Azure, and Apple only after their console credentials are ready.

### Function secrets

Set secrets through the CLI; do not create a committed `.env`:

```bash
npx supabase secrets set \
  CALENDAR_STATE_SECRET='<random-32-byte-value>' \
  INTEGRATION_TOKEN_KEY='<random-32-byte-value>' \
  GOOGLE_CALENDAR_CLIENT_ID='<value>' \
  GOOGLE_CALENDAR_CLIENT_SECRET='<value>' \
  MICROSOFT_CALENDAR_CLIENT_ID='<value>' \
  MICROSOFT_CALENDAR_CLIENT_SECRET='<value>' \
  APPLE_TEAM_ID='<value>' \
  APPLE_KEY_ID='<value>' \
  APPLE_SERVICES_ID='com.norfold.app.auth' \
  APPLE_PRIVATE_KEY='<p8-content>' \
  FIREBASE_PROJECT_ID='<value>' \
  FIREBASE_SERVICE_ACCOUNT_JSON='<single-line-json>' \
  STRIPE_SECRET_KEY='<test-key>' \
  STRIPE_WEBHOOK_SECRET='<test-signing-secret>' \
  STRIPE_PRO_PRICE_ID='<test-price-id>' \
  STRIPE_TEAM_PRICE_ID='<test-price-id>' \
  PLAY_SERVICE_ACCOUNT_JSON='<single-line-json>'
```

Generate random application secrets with `openssl rand -base64 32`. Deploy all functions:

```bash
for function in calendar-callback stripe-webhook notification-token notification-dispatch invitation sync-operations play-verify play-rtdn; do
  npx supabase functions deploy "$function" --project-ref <SUPABASE_REF>
done
```

### RLS acceptance gate

1. Create two unrelated test users and two workspaces.
2. For each exposed table, verify user A cannot select, insert, update, or delete user B's rows.
3. Verify a workspace member can read only their workspace and that role restrictions prevent member-level invitation/administration writes.
4. Verify personal workspace payload rows contain ciphertext and team rows contain the expected RLS-readable JSON.
5. Verify storage bucket policies enforce the same workspace membership boundary.
6. Do not put the publishable key into `norfold.properties` until these checks pass.

References: [Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [Function secrets](https://supabase.com/docs/guides/functions/secrets).

## 2. Google Cloud and Firebase

### Project and APIs

1. Create a fresh Google Cloud project named **Norfold Alpha** and record its project ID.
2. Add Firebase to that same project.
3. Enable Google Drive API, Google Calendar API, Firebase Cloud Messaging API, Google Play Android Developer API, and Pub/Sub API.
4. In Google Auth Platform, choose External > Testing and add every Alpha tester. Testing is limited to 100 listed users and sensitive-scope refresh grants can expire after seven days.
5. Set the exact Norfold name/logo, homepage, privacy, and terms URLs.
6. Register only `openid`, `email`, `profile`, `drive.appdata`, `calendar.events`, and `calendar.calendarlist.readonly`.

### OAuth clients

Create these separate clients:

| Client | Type | Configuration |
| --- | --- | --- |
| Norfold Android Debug | Android | Package `com.norfold.app`, debug SHA-1 above |
| Norfold Android Upload | Android | Package `com.norfold.app`, upload-key SHA-1 |
| Norfold Android Play | Android | Package `com.norfold.app`, Play App Signing SHA-1 |
| Norfold Supabase Auth | Web | Redirect `https://<SUPABASE_REF>.supabase.co/auth/v1/callback` |
| Norfold Calendar Alpha | Web | Redirect `https://<SUPABASE_REF>.supabase.co/functions/v1/calendar-callback` |

The **Norfold Supabase Auth** Web client ID is `GOOGLE_SERVER_CLIENT_ID`. Put its client secret only in the Supabase Google provider. The Calendar client ID/secret go only into Function secrets.

### Firebase Android and FCM

1. Add Firebase Android app `com.norfold.app`.
2. Add debug, upload, and Play signing SHA-1/SHA-256 fingerprints.
3. Download `google-services.json` to `apps/android/google-services.json`.
4. Enable FCM HTTP v1.
5. Create a least-privilege server service account able to send FCM messages. Store its JSON only in `FIREBASE_SERVICE_ACCOUNT_JSON`.
6. Do not place the service-account JSON in the APK or repository.

Drive synchronization continues to use the hidden `appDataFolder` and the non-sensitive `drive.appdata` scope. Identity uses Credential Manager; Drive authorization is requested separately through AuthorizationClient when the user connects Drive.

References: [Google testing audience](https://support.google.com/cloud/answer/15549945), [verification requirements](https://support.google.com/cloud/answer/13464321), [Firebase Android setup](https://firebase.google.com/docs/android/setup), [FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api), [Drive app data](https://developers.google.com/workspace/drive/api/guides/appdata).

## 3. Play Console

1. Create **Norfold**, package `com.norfold.app`, free, default language English.
2. Enable Google-managed Play App Signing. Keep a separate private upload key.
3. Record upload-key and Play App Signing SHA-1/SHA-256. Add both sets to Google OAuth and Firebase.
4. Build and upload the signed AAB to Internal Testing; create the Alpha tester list (maximum 100).
5. Complete privacy policy, app access, content rating, ads, target audience, and Data safety.
6. Create draft subscriptions `norfold_pro` and `norfold_team`. Billing UI remains disabled for Local Alpha.
7. Link Play Console to the same Google Cloud project.
8. Create a dedicated purchase-verification service account and grant only the required Play Console access. Store its JSON as `PLAY_SERVICE_ACCOUNT_JSON`.
9. Create a Pub/Sub topic for Real-time Developer Notifications. Push to the deployed `play-rtdn` function and configure a verification token/JWT policy.
10. Verify subscriptions server-side with `purchases.subscriptionsv2.get`; never grant entitlements from client claims.

References: [Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756), [Internal testing](https://support.google.com/googleplay/android-developer/answer/9845334), [Play backend integration](https://developer.android.com/google/play/billing/backend).

## 4. Microsoft Entra and Outlook

### Supabase authentication registration

1. Create **Norfold Auth Alpha**.
2. Select organizational directories plus personal Microsoft accounts.
3. Add Web redirect `https://<SUPABASE_REF>.supabase.co/auth/v1/callback`.
4. Create a client secret with no more than a 12-month lifetime; schedule rotation 30 days before expiry.
5. Configure Supabase Azure provider with application ID, secret value, and the `common` tenant URL.

### Calendar registration

1. Create separate registration **Norfold Calendar Alpha**.
2. Add Web redirect `https://<SUPABASE_REF>.supabase.co/functions/v1/calendar-callback`.
3. Add delegated permissions `openid`, `profile`, `email`, `offline_access`, `User.Read`, and `Calendars.ReadWrite`.
4. Store its client ID/secret only in the corresponding Supabase Function secrets.

Reference: [Supabase Azure login](https://supabase.com/docs/guides/auth/social-login/auth-azure).

## 5. Apple

1. Use a paid Apple Developer account.
2. Register App ID `com.norfold.app` and enable Sign in with Apple.
3. Register Services ID `com.norfold.app.auth` linked to that App ID.
4. Add domain `<SUPABASE_REF>.supabase.co` and return URL `https://<SUPABASE_REF>.supabase.co/auth/v1/callback`.
5. Generate a Sign in with Apple key. Record Team ID and Key ID and store the `.p8` securely.
6. Generate the client secret and configure the Supabase Apple provider.
7. Rotate the Apple client secret every five months because it cannot exceed six months.
8. Android uses browser/PKCE; no Apple private key enters the APK.

Reference: [Supabase Apple login](https://supabase.com/docs/guides/auth/social-login/auth-apple).

## 6. Stripe and Resend

### Stripe test mode

1. Create draft Pro and Team products and recurring test prices.
2. Register `https://<SUPABASE_REF>.supabase.co/functions/v1/stripe-webhook`.
3. Subscribe to checkout completion and subscription created/updated/deleted events.
4. Store test secret key, webhook signing secret, and price IDs in Supabase secrets.
5. Replay duplicate webhook deliveries and verify idempotency before enabling any billing UI.

### Email

Resend, email sign-in, invitations by email, and email notifications remain disabled for Local Alpha. When an owned domain is available, verify a dedicated sending subdomain with SPF and DKIM before enabling email delivery. Reference: [Resend domain setup](https://resend.com/docs/dashboard/domains/introduction).

## Credential Return Worksheet

Return only the public values/files below through the agreed secure channel. Put private values directly into the provider console or Supabase secrets rather than sending them for Android wiring.

```text
SUPABASE_REF=
SUPABASE_URL=
SUPABASE_PUBLISHABLE_KEY=

GOOGLE_SERVER_CLIENT_ID=
GOOGLE_CALENDAR_CLIENT_ID=                 # Supabase secret, not Android
GOOGLE_CALENDAR_CLIENT_SECRET=             # Supabase secret, not Android
GOOGLE_CLOUD_PROJECT_ID=
FIREBASE_PROJECT_ID=
GOOGLE_SERVICES_JSON_PATH=

UPLOAD_KEY_SHA1=
UPLOAD_KEY_SHA256=
PLAY_APP_SIGNING_SHA1=
PLAY_APP_SIGNING_SHA256=

MICROSOFT_AUTH_APPLICATION_ID=
MICROSOFT_CALENDAR_CLIENT_ID=              # Supabase secret, not Android
APPLE_TEAM_ID=                             # Supabase secret
APPLE_KEY_ID=                              # Supabase secret

STRIPE_PRO_PRICE_ID=                       # Supabase secret
STRIPE_TEAM_PRICE_ID=                      # Supabase secret
```

## Final Operator Checklist

- [ ] Fresh Supabase, Google/Firebase, Entra, Apple, Stripe, and Play Alpha projects are used.
- [ ] Email, anonymous auth, Resend, and production billing UI remain disabled.
- [ ] Every exposed Supabase table passes two-user RLS isolation.
- [ ] No private credential appears in Git history, Gradle fields, resources, APK, or AAB.
- [ ] `norfold.properties` contains public values only and remains gitignored.
- [ ] Debug, upload, and Play fingerprints exist in Google OAuth and Firebase.
- [ ] Google identity, Drive app-data, Google Calendar, Outlook Calendar, Apple/Microsoft auth, FCM, Stripe, Play verification, callback replay, token revocation, and offline recovery are sandbox-tested.
- [ ] Internal-track install succeeds before Alpha acceptance.
