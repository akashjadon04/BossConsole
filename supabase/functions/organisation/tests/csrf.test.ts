/**
 * CSRF checks. The interesting assertions are the ones about SAME-ORIGIN
 * attackers, because that is the case SameSite does not cover and the reason
 * the nonce lives inside the signed cookie.
 */

import { assertEquals } from "@std/assert"
import { checkCsrf, CSRF_FIELD, originIsSameSite } from "../utils/csrf.ts"
import type { SessionPayload } from "../utils/session.ts"
import { FIXTURE } from "./helpers/mocks.ts"

const ORIGIN = "https://api.risaboss.com"

function session(csrf = "nonce-a"): SessionPayload {
  return {
    sub: FIXTURE.userId,
    org: FIXTURE.orgId,
    slug: FIXTURE.slug,
    csrf,
    pur: "org_admin",
    iat: 1_800_000_000,
    exp: 1_800_001_800,
  }
}

Deno.test("a well-formed same-origin post passes", () => {
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "same-origin",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    null,
  )
})

Deno.test("a token from another session is refused", () => {
  // The whole point of binding the nonce into the signed cookie: an attacker
  // who obtains SOME valid nonce cannot use it against a different session.
  assertEquals(
    checkCsrf({
      session: session("nonce-a"),
      submitted: "nonce-b",
      secFetchSite: "same-origin",
      origin: ORIGIN,
      expectedOrigin: ORIGIN,
    }),
    "bad_token",
  )
})

Deno.test("a missing token is refused", () => {
  for (const submitted of [undefined, null, "", 42, {}]) {
    assertEquals(
      checkCsrf({
        session: session(),
        submitted,
        secFetchSite: "same-origin",
        origin: ORIGIN,
        expectedOrigin: ORIGIN,
      }),
      "missing_token",
      `should refuse: ${JSON.stringify(submitted)}`,
    )
  }
})

Deno.test("a cross-site post is refused before the token is even considered", () => {
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: "cross-site",
      origin: "https://evil.example.com",
      expectedOrigin: ORIGIN,
    }),
    "bad_origin",
  )
})

Deno.test("a request with neither Sec-Fetch-Site nor Origin is refused", () => {
  // Stricter than the "absent Origin means same-origin" convention: a browser
  // form post always carries one of the two, so this is a non-browser client.
  assertEquals(originIsSameSite(null, null, ORIGIN), false)
  assertEquals(
    checkCsrf({
      session: session(),
      submitted: "nonce-a",
      secFetchSite: null,
      origin: null,
      expectedOrigin: ORIGIN,
    }),
    "bad_origin",
  )
})

Deno.test("Sec-Fetch-Site is trusted over a mismatched Origin", () => {
  // Sec-Fetch-Site is set by the browser and unforgeable from page script, so
  // it decides when present.
  assertEquals(originIsSameSite("same-origin", "https://evil.example.com", ORIGIN), true)
  assertEquals(originIsSameSite("cross-site", ORIGIN, ORIGIN), false)
})

Deno.test("Sec-Fetch-Site: none is accepted as a user-initiated navigation", () => {
  // Typed URL or bookmark. It never accompanies a cross-origin form post.
  assertEquals(originIsSameSite("none", null, ORIGIN), true)
})

Deno.test("Origin is the fallback when Sec-Fetch-Site is absent", () => {
  assertEquals(originIsSameSite(null, ORIGIN, ORIGIN), true)
  assertEquals(originIsSameSite(null, "https://evil.example.com", ORIGIN), false)
  // Case-insensitive: origins are compared as origins, not as strings.
  assertEquals(originIsSameSite(null, "HTTPS://API.RISABOSS.COM", ORIGIN), true)
})

Deno.test("the field name is stable", () => {
  // The view and the guard have to agree; a rename in one place only would
  // silently reject every post.
  assertEquals(CSRF_FIELD, "csrf_token")
})
