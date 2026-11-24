/**
 * Session cookie: minting, verification, and the properties that make a
 * stateless cookie safe to use for authentication.
 */

import { assert, assertEquals, assertNotEquals, assertRejects } from "@std/assert"
import {
  clearCookieHeader,
  cookieName,
  cookieValues,
  mintSession,
  newCsrfToken,
  readSession,
  SESSION_TTL_SECONDS,
  sessionCookieHeader,
  verifySession,
} from "../utils/session.ts"
import { MissingSessionSecretError } from "../utils/config.ts"
import { FIXTURE, TEST_SECRET, withTestEnv } from "./helpers/mocks.ts"

const NOW = 1_800_000_000

function payload() {
  return {
    sub: FIXTURE.userId,
    org: FIXTURE.orgId,
    slug: FIXTURE.slug,
    csrf: "csrf-nonce-value",
    pur: "org_view",
  }
}

Deno.test("a minted session round-trips", async () => {
  const restore = withTestEnv()
  try {
    const value = await mintSession(payload(), NOW)
    const session = await verifySession(value, NOW + 10)
    assert(session)
    assertEquals(session.sub, FIXTURE.userId)
    assertEquals(session.org, FIXTURE.orgId)
    assertEquals(session.slug, FIXTURE.slug)
    assertEquals(session.exp, NOW + SESSION_TTL_SECONDS)
  } finally {
    restore()
  }
})

Deno.test("the token carries no algorithm field to negotiate", async () => {
  const restore = withTestEnv()
  try {
    const value = await mintSession(payload(), NOW)
    const decoded = JSON.parse(atob(value.split(".")[0].replace(/-/g, "+").replace(/_/g, "/")))
    assertEquals(Object.hasOwn(decoded, "alg"), false)
    assertEquals(Object.hasOwn(decoded, "typ"), false)
    // Two segments, not three: there is no header segment at all.
    assertEquals(value.split(".").length, 2)
  } finally {
    restore()
  }
})

Deno.test("a tampered payload is rejected", async () => {
  const restore = withTestEnv()
  try {
    const value = await mintSession(payload(), NOW)
    const [encoded, sig] = value.split(".")
    const decoded = JSON.parse(atob(encoded.replace(/-/g, "+").replace(/_/g, "/")))
    decoded.org = FIXTURE.otherOrgId
    const forged = btoa(JSON.stringify(decoded))
      .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
    assertEquals(await verifySession(`${forged}.${sig}`, NOW + 10), null)
  } finally {
    restore()
  }
})

Deno.test("an injected admin claim is dropped, not honoured", async () => {
  const restore = withTestEnv()
  try {
    // Signed with the real key, so this is the strongest version of the attack:
    // someone who can mint a cookie still cannot grant themselves anything,
    // because nothing downstream reads a privilege claim from the payload.
    const value = await mintSession(
      { ...payload(), ...{ admin: true, is_admin: true } } as never,
      NOW,
    )
    const session = await verifySession(value, NOW + 10)
    assert(session)
    assertEquals(Object.hasOwn(session, "admin"), false)
    assertEquals(Object.hasOwn(session, "is_admin"), false)
    assertEquals(Object.keys(session).sort(), ["csrf", "exp", "iat", "org", "pur", "slug", "sub"])
  } finally {
    restore()
  }
})

Deno.test("an expired session is rejected", async () => {
  const restore = withTestEnv()
  try {
    const value = await mintSession(payload(), NOW)
    assertEquals(await verifySession(value, NOW + SESSION_TTL_SECONDS), null)
    assertEquals(await verifySession(value, NOW + SESSION_TTL_SECONDS + 1), null)
    assert(await verifySession(value, NOW + SESSION_TTL_SECONDS - 1))
  } finally {
    restore()
  }
})

Deno.test("a payload claiming a longer life than the TTL is rejected", async () => {
  const restore = withTestEnv()
  try {
    // Hand-rolled with a far-future exp and signed correctly. Even a valid
    // signature cannot buy more than SESSION_TTL_SECONDS.
    const body = {
      ...payload(),
      iat: NOW,
      exp: NOW + SESSION_TTL_SECONDS * 100,
    }
    const encoded = btoa(JSON.stringify(body))
      .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
    const key = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(TEST_SECRET),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    )
    const sig = new Uint8Array(
      await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(encoded)),
    )
    let binary = ""
    for (const b of sig) binary += String.fromCharCode(b)
    const sigEncoded = btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")

    assertEquals(await verifySession(`${encoded}.${sigEncoded}`, NOW + 10), null)
  } finally {
    restore()
  }
})

Deno.test("a session signed with the previous key still verifies", async () => {
  const restore = withTestEnv()
  try {
    const value = await mintSession(payload(), NOW)
    // Rotate: the signing key becomes the previous key.
    Deno.env.set("ORG_SESSION_SECRET", "a-brand-new-signing-key-0123456789abcdef")
    Deno.env.set("ORG_SESSION_SECRET_PREV", TEST_SECRET)
    assert(await verifySession(value, NOW + 10))

    // ...but only while it is still listed.
    Deno.env.delete("ORG_SESSION_SECRET_PREV")
    assertEquals(await verifySession(value, NOW + 10), null)
  } finally {
    restore()
  }
})

Deno.test("garbage inputs are rejected without throwing", async () => {
  const restore = withTestEnv()
  try {
    for (
      const bad of ["", ".", "a.b", "....", "no-dot", "!!!.???", "a.b.c", `${"x".repeat(500)}.y`]
    ) {
      assertEquals(await verifySession(bad, NOW), null, `should reject: ${bad}`)
    }
  } finally {
    restore()
  }
})

Deno.test("a missing secret throws rather than falling back", async () => {
  const restore = withTestEnv()
  try {
    Deno.env.delete("ORG_SESSION_SECRET")
    await assertRejects(() => mintSession(payload(), NOW), MissingSessionSecretError)
  } finally {
    restore()
  }
})

Deno.test("a short secret is refused as a typo guard", async () => {
  const restore = withTestEnv()
  try {
    Deno.env.set("ORG_SESSION_SECRET", "changeme")
    await assertRejects(() => mintSession(payload(), NOW), MissingSessionSecretError)
  } finally {
    restore()
  }
})

Deno.test("every cookie of the same name is tried, so a planted one cannot shadow", async () => {
  const restore = withTestEnv()
  try {
    const real = await mintSession(payload(), NOW)
    const name = cookieName(false)

    // The planted value comes FIRST, which is what a header-order-dependent
    // reader would pick.
    const header = `${name}=garbage-planted-value; other=x; ${name}=${real}`
    assertEquals(cookieValues(header, name).length, 2)

    const session = await readSession(header, false, NOW + 10)
    assert(session, "the real cookie must still be found behind the planted one")
    assertEquals(session.org, FIXTURE.orgId)
  } finally {
    restore()
  }
})

Deno.test("the cookie name drops the __Secure- prefix over plain http", () => {
  assertEquals(cookieName(true), "__Secure-boss_org")
  assertEquals(cookieName(false), "boss_org")
  assertNotEquals(cookieName(true), cookieName(false))
})

Deno.test("the Set-Cookie carries the attributes the design depends on", () => {
  const secure = sessionCookieHeader("v", true, "/functions/v1/organisation")
  assert(secure.includes("__Secure-boss_org=v"))
  assert(secure.includes("Path=/functions/v1/organisation"))
  assert(secure.includes("HttpOnly"))
  assert(secure.includes("SameSite=Lax"))
  assert(secure.includes("Secure"))
  assert(secure.includes(`Max-Age=${SESSION_TTL_SECONDS}`))

  // Over http the Secure attribute must be absent, or the browser discards the
  // cookie and the handoff loops forever.
  const insecure = sessionCookieHeader("v", false, "/functions/v1/organisation")
  assertEquals(insecure.includes("Secure"), false)
  assert(insecure.startsWith("boss_org=v"))
})

Deno.test("the clearing cookie expires immediately", () => {
  const header = clearCookieHeader(true, "/functions/v1/organisation")
  assert(header.includes("Max-Age=0"))
  assert(header.includes("HttpOnly"))
})

Deno.test("csrf nonces are unique", () => {
  const seen = new Set<string>()
  for (let i = 0; i < 200; i++) seen.add(newCsrfToken())
  assertEquals(seen.size, 200)
})
