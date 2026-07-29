// telegram-auth-webhook — receives Telegram Bot API updates for @Norfold_auth_bot.
//
// verify_jwt is OFF (Telegram has no Supabase JWT), but this endpoint is NOT
// unauthenticated: every request must carry the secret we handed Telegram in
// setWebhook, echoed back in the X-Telegram-Bot-Api-Secret-Token header. We
// compare it in constant time and reject anything else before doing any work.
//
// The bot does exactly one thing: phone verification. /start <token> opens a
// session; sharing your own contact completes it. Everything else gets a single
// canned reply. The bot token and webhook secret live only in function secrets
// and are never logged.
import { serviceClient } from "../_shared/supabase.ts";
import { requiredEnv } from "../_shared/http.ts";

// Deep link back into the app. Host is "verify" (NOT "auth" — that host is
// consumed by Supabase's handleDeeplinks) and carries no trust: the app must
// re-query Supabase for the real status.
const RETURN_DEEP_LINK = "norfold://verify/telegram";

type TgUser = { id: number };
type TgChat = { id: number; type: string };
type TgContact = { phone_number: string; user_id?: number };
type TgMessage = {
  message_id: number;
  from?: TgUser;
  chat: TgChat;
  text?: string;
  contact?: TgContact;
};
type TgUpdate = { update_id: number; message?: TgMessage };

function timingSafeEqual(a: string, b: string): boolean {
  const enc = new TextEncoder();
  const ab = enc.encode(a);
  const bb = enc.encode(b);
  // Compare against a fixed length so we never early-return on length.
  let diff = ab.length ^ bb.length;
  const len = Math.max(ab.length, bb.length);
  for (let i = 0; i < len; i += 1) diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0);
  return diff === 0;
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

// Telegram's contact.phone_number has no leading "+" and is otherwise the raw
// digits the account is registered with. We do NOT infer a country from a
// locale; we only normalise to E.164 shape (leading "+", digits only).
function toE164(raw: string): string | null {
  const digits = raw.replace(/[^\d]/g, "");
  if (digits.length < 8 || digits.length > 15) return null;
  return `+${digits}`;
}

function maskPhone(e164: string): string {
  return e164.length <= 5 ? "***" : `${e164.slice(0, 4)}••••${e164.slice(-2)}`;
}

async function callTelegram(botToken: string, method: string, payload: unknown): Promise<void> {
  const response = await fetch(`https://api.telegram.org/bot${botToken}/${method}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!response.ok) {
    // Never include the token; log method + status only.
    console.error(`telegram ${method} failed: ${response.status}`);
  }
}

Deno.serve(async (request) => {
  const botToken = requiredEnv("TELEGRAM_AUTH_BOT_TOKEN");

  // 1. Authenticate the caller as Telegram via the shared secret.
  const presented = request.headers.get("X-Telegram-Bot-Api-Secret-Token") ?? "";
  if (!timingSafeEqual(presented, requiredEnv("TELEGRAM_WEBHOOK_SECRET"))) {
    return new Response("forbidden", { status: 403 });
  }

  let update: TgUpdate;
  try {
    update = await request.json();
  } catch {
    return new Response("bad request", { status: 400 });
  }

  const supabase = serviceClient();

  // 2. Idempotency: record update_id once; replays become no-ops. We ACK (200)
  // on replay so Telegram stops retrying.
  const { error: dedupeError } = await supabase
    .from("telegram_processed_updates")
    .insert({ update_id: update.update_id });
  if (dedupeError) {
    // Unique violation => already processed. Any other error: let Telegram retry.
    if (dedupeError.code === "23505") return new Response("ok");
    console.error("dedupe insert failed:", dedupeError.message);
    return new Response("error", { status: 500 });
  }

  const message = update.message;
  // Only private-chat messages matter; ignore everything else silently.
  if (!message || message.chat.type !== "private" || !message.from) {
    return new Response("ok");
  }
  const chatId = message.chat.id;
  const fromId = message.from.id;

  try {
    // 3a. /start <token> — open the session and ask for the contact.
    const startMatch = message.text?.match(/^\/start(?:\s+(\S+))?/);
    if (startMatch) {
      const rawToken = startMatch[1];
      if (!rawToken) {
        await callTelegram(botToken, "sendMessage", {
          chat_id: chatId,
          text: "This bot is only for Norfold phone verification. Start verification from the Norfold app.",
        });
        return new Response("ok");
      }

      const tokenHash = await sha256Hex(rawToken);
      const { data: session } = await supabase
        .from("telegram_verification_sessions")
        .select("id, status, expires_at")
        .eq("token_hash", tokenHash)
        .maybeSingle();

      if (!session || (session.status !== "PENDING" && session.status !== "STARTED") ||
          new Date(session.expires_at).getTime() <= Date.now()) {
        await callTelegram(botToken, "sendMessage", {
          chat_id: chatId,
          text: "This verification link is invalid or has expired. Please start again from the Norfold app.",
        });
        return new Response("ok");
      }

      await supabase
        .from("telegram_verification_sessions")
        .update({ status: "STARTED", telegram_user_id: fromId, telegram_chat_id: chatId })
        .eq("id", session.id);

      await callTelegram(botToken, "sendMessage", {
        chat_id: chatId,
        text: "Verify your phone for Norfold.\n\nTap the button below to share the phone number attached to this Telegram account.",
        reply_markup: {
          keyboard: [[{ text: "📱 Share phone number", request_contact: true }]],
          resize_keyboard: true,
          one_time_keyboard: true,
          is_persistent: false,
        },
      });
      return new Response("ok");
    }

    // 3b. Contact shared — validate ownership and complete.
    if (message.contact) {
      const contact = message.contact;
      // The contact must be the sender's OWN account. This is the single check
      // that stops someone forwarding another person's contact card.
      if (!contact.user_id || contact.user_id !== fromId) {
        await callTelegram(botToken, "sendMessage", {
          chat_id: chatId,
          text: "Please tap the “Share phone number” button to share your own number — forwarded or typed contacts can't be used.",
        });
        return new Response("ok");
      }

      const phone = toE164(contact.phone_number);
      if (!phone) {
        await callTelegram(botToken, "sendMessage", {
          chat_id: chatId,
          text: "That phone number couldn't be read. Please start again from the Norfold app.",
          reply_markup: { remove_keyboard: true },
        });
        return new Response("ok");
      }

      // Find this Telegram user's active STARTED session (bound at /start).
      const { data: session } = await supabase
        .from("telegram_verification_sessions")
        .select("token_hash")
        .eq("telegram_user_id", fromId)
        .eq("status", "STARTED")
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle();

      if (!session) {
        await callTelegram(botToken, "sendMessage", {
          chat_id: chatId,
          text: "We couldn't find an active verification. Please start again from the Norfold app.",
          reply_markup: { remove_keyboard: true },
        });
        return new Response("ok");
      }

      const { data: outcome, error: rpcError } = await supabase.rpc("telegram_complete_verification", {
        p_token_hash: session.token_hash,
        p_telegram_user_id: fromId,
        p_phone_e164: phone,
      });
      if (rpcError) throw rpcError;

      const messages: Record<string, string> = {
        VERIFIED: "✓ Phone verified for Norfold.\n\nYou can return to the app now.",
        PHONE_ALREADY_LINKED: "This phone number is already linked to a different Norfold account.",
        SUBJECT_CONFLICT: "This Telegram account is already linked to a different Norfold account.",
        EXPIRED: "This verification has expired. Please start again from the Norfold app.",
        NOT_FOUND: "We couldn't find an active verification. Please start again from the Norfold app.",
      };
      const status = String(outcome ?? "NOT_FOUND");
      const verified = status === "VERIFIED";
      console.log(`telegram verify ${status} for ${maskPhone(phone)}`);

      await callTelegram(botToken, "sendMessage", {
        chat_id: chatId,
        text: messages[status] ?? messages.NOT_FOUND,
        reply_markup: verified
          ? { inline_keyboard: [[{ text: "Return to Norfold", url: RETURN_DEEP_LINK }]] }
          : { remove_keyboard: true },
      });
      return new Response("ok");
    }

    // 3c. Anything else in a private chat: single canned reply.
    await callTelegram(botToken, "sendMessage", {
      chat_id: chatId,
      text: "This bot is only for Norfold phone verification. Start verification from the Norfold app.",
    });
    return new Response("ok");
  } catch (error) {
    console.error("telegram-auth-webhook processing failed:", error instanceof Error ? error.message : error);
    // Return 200 anyway: the update_id is already recorded, and we don't want an
    // infinite Telegram retry loop over a transient send failure.
    return new Response("ok");
  }
});
