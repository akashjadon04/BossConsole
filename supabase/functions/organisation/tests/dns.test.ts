/**
 * Hostname validation and verification-record parsing.
 *
 * The network path is not exercised here - it is a real resolver call. What is
 * tested is everything that decides WHETHER and WITH WHAT we would call it.
 */

import { assertEquals } from "@std/assert"
import { isValidHostname, verifyDomainToken } from "../services/dns.ts"
import { extractToken } from "../routes/domains.ts"

Deno.test("valid hostnames are accepted", () => {
  for (const host of ["example.com", "sub.example.com", "a-b.example.co.uk", "xn--80ak6aa92e.com"]) {
    assertEquals(isValidHostname(host), true, `should accept: ${host}`)
  }
})

Deno.test("anything that is not a plain hostname is refused", () => {
  for (
    const host of [
      "",
      "localhost", // no dot: not a registrable domain
      ".example.com",
      "example.com.", // trailing dot would change the query
      "*.example.com",
      "exa mple.com",
      "example.com/path",
      "http://example.com",
      "-bad.example.com",
      "bad-.example.com",
      "exämple.com", // non-ASCII: must be punycoded by the caller
      `${"a".repeat(64)}.com`, // label over 63
      `${"a".repeat(250)}.com`, // name over 253
    ]
  ) {
    assertEquals(isValidHostname(host), false, `should refuse: ${host}`)
  }
})

Deno.test("verification is refused for an invalid hostname without touching DNS", async () => {
  // No resolver call can happen here, so this also proves the guard runs first.
  assertEquals((await verifyDomainToken("not a host", "tok")).verified, false)
  assertEquals((await verifyDomainToken("example.com", "")).verified, false)
})

Deno.test("extractToken reads the token out of the record value", () => {
  assertEquals(extractToken("boss-org-verification=abc123"), "abc123")
  assertEquals(extractToken("boss-org-verification=abc123  "), "abc123")
})

Deno.test("extractToken refuses a record that is not ours", () => {
  assertEquals(extractToken("v=spf1 include:example.com"), null)
  assertEquals(extractToken("boss-org-verification="), null)
  assertEquals(extractToken(""), null)
  // Prefix must be at the START: a TXT record is attacker-writable on a domain
  // we do not yet trust, so a substring match would accept a planted record.
  assertEquals(extractToken("x boss-org-verification=abc"), null)
})
