import {
  assertEquals,
  assertRejects,
  assertThrows,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  parseUserinfoSubject,
  providerAccountSubject,
  userinfoEndpoint,
  type UserinfoFetch,
} from "./oidc_userinfo.ts";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

Deno.test("a present string subject is the account key", () => {
  assertEquals(parseUserinfoSubject({ sub: "118273645" }), "118273645");
});

// Every one of these used to become the literal "calendar-account", which is a single durable
// key shared by every account that reached it.
Deno.test("no usable subject is rejected rather than defaulted", () => {
  const unusable: unknown[] = [
    null,
    undefined,
    {},
    { sub: "" },
    { sub: "   " },
    { sub: 118273645 },
    { sub: null },
    { sub: { id: "118273645" } },
    "118273645",
  ];
  for (const body of unusable) {
    assertThrows(
      () => parseUserinfoSubject(body),
      Error,
      "Provider identity is unavailable",
    );
  }
});

Deno.test("each provider is asked at its own OIDC userinfo endpoint", async () => {
  const asked: Array<{ endpoint: string; accessToken: string }> = [];
  const userinfo: UserinfoFetch = (endpoint, accessToken) => {
    asked.push({ endpoint, accessToken });
    return Promise.resolve(jsonResponse({ sub: "subject-1" }));
  };

  assertEquals(
    await providerAccountSubject("google_calendar", "token-g", userinfo),
    "subject-1",
  );
  assertEquals(
    await providerAccountSubject("outlook_calendar", "token-m", userinfo),
    "subject-1",
  );

  assertEquals(asked, [
    { endpoint: userinfoEndpoint("google_calendar"), accessToken: "token-g" },
    { endpoint: userinfoEndpoint("outlook_calendar"), accessToken: "token-m" },
  ]);
  assertEquals(asked[0].endpoint.startsWith("https://"), true);
  assertEquals(asked[1].endpoint.startsWith("https://"), true);
});

Deno.test("a failed userinfo call establishes no identity", async () => {
  const userinfo: UserinfoFetch = () =>
    Promise.resolve(jsonResponse({ sub: "leaked" }, 401));
  await assertRejects(
    () => providerAccountSubject("google_calendar", "token", userinfo),
    Error,
    "Provider identity is unavailable",
  );
});

Deno.test("a non-JSON userinfo body establishes no identity", async () => {
  const userinfo: UserinfoFetch = () =>
    Promise.resolve(new Response("<html>proxy error</html>", { status: 200 }));
  await assertRejects(
    () => providerAccountSubject("google_calendar", "token", userinfo),
    Error,
    "Provider identity is unavailable",
  );
});

Deno.test("an absent access token is never sent to the provider", async () => {
  let called = false;
  const userinfo: UserinfoFetch = () => {
    called = true;
    return Promise.resolve(jsonResponse({ sub: "subject-1" }));
  };
  await assertRejects(
    () => providerAccountSubject("google_calendar", "", userinfo),
    Error,
    "Provider identity is unavailable",
  );
  assertEquals(called, false);
});
