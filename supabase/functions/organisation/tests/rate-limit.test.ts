/**
 * The rate limiter, including the failure mode a naive cap would have.
 */

import { assert, assertEquals } from "@std/assert"
import { clientKey, rateLimit, resetRateLimits } from "../utils/rate-limit.ts"

Deno.test("requests are allowed up to the limit and refused after", () => {
  resetRateLimits()
  const now = 1_000_000

  for (let i = 0; i < 5; i++) {
    assertEquals(rateLimit("k", 5, 60, now).allowed, true, `request ${i + 1} should pass`)
  }

  const refused = rateLimit("k", 5, 60, now)
  assertEquals(refused.allowed, false)
  assert(refused.retryAfterSeconds > 0 && refused.retryAfterSeconds <= 60)
})

Deno.test("the window resets", () => {
  resetRateLimits()
  const now = 1_000_000
  for (let i = 0; i < 5; i++) rateLimit("k", 5, 60, now)
  assertEquals(rateLimit("k", 5, 60, now).allowed, false)
  assertEquals(rateLimit("k", 5, 60, now + 60_001).allowed, true)
})

Deno.test("keys are independent", () => {
  resetRateLimits()
  const now = 1_000_000
  for (let i = 0; i < 5; i++) rateLimit("a", 5, 60, now)
  assertEquals(rateLimit("a", 5, 60, now).allowed, false)
  assertEquals(rateLimit("b", 5, 60, now).allowed, true)
})

Deno.test("a flood of distinct keys does not disable the limiter", () => {
  resetRateLimits()
  const now = 1_000_000

  // Every key is fresh and nothing has expired, so the eviction pass frees
  // nothing. Without the clear-on-full fallback the map would sit at MAX_KEYS
  // and every subsequent key would bypass the limiter entirely.
  for (let i = 0; i < 10_050; i++) rateLimit(`flood-${i}`, 1, 60, now)

  const victim = "still-limited"
  assertEquals(rateLimit(victim, 1, 60, now).allowed, true)
  assertEquals(rateLimit(victim, 1, 60, now).allowed, false)
})

Deno.test("clientKey prefers the leftmost forwarded address", () => {
  assertEquals(
    clientKey(new Headers({ "x-forwarded-for": "203.0.113.5, 70.41.3.18" })),
    "203.0.113.5",
  )
  assertEquals(clientKey(new Headers({ "cf-connecting-ip": "203.0.113.9" })), "203.0.113.9")
  assertEquals(clientKey(new Headers()), "unknown")
})
