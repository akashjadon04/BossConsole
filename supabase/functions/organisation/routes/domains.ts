/**
 * POST /o/:slug/admin/domains/* -- claiming and verifying email domains.
 *
 * A verified domain is a powerful thing: it lets an organisation absorb every
 * future signup with a matching address. So verification proves control of the
 * DNS zone, and two rules protect it.
 *
 * RULE 1 -- AUTHORIZE AGAINST THE ROW'S ORG, NOT THE COOKIE'S. Every handler
 * that takes a `domain_id` loads the domain from THIS org's list first and
 * refuses an id that is not in it. Without that, an admin of org A could
 * verify, re-point or delete org B's domain by guessing a row id: the CSRF
 * token would be valid, the admin probe would pass (they really are an admin of
 * something), and the id would be the only thing tying the request to a row.
 * The RPCs re-check too; this is the belt to their braces, and it is also what
 * lets the page give an honest answer rather than a generic rejection.
 *
 * RULE 2 -- THE DNS ANSWER IS NEVER ECHOED. verifyDomainToken returns a
 * boolean and nothing else. Returning the records seen would make this an open
 * resolver behind an authenticated endpoint.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { callForActor, callRpc } from "../utils/org-rpc.ts"
import { listDomains, type OrgDomain } from "../services/org.ts"
import { isValidHostname, verifyDomainToken } from "../services/dns.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { field, uuidField } from "../utils/request.ts"
import { prepare, redirectTo } from "./admin-actions.ts"

/**
 * DNS probes are the one action here that reaches outside our infrastructure,
 * so they get their own, much tighter budget: 10 per minute per client and 5
 * per minute for any single domain, the latter keyed globally so a pool of
 * clients cannot spread the load across the per-client limit.
 */
const DNS_CLIENT_LIMIT = 10
const DNS_DOMAIN_LIMIT = 5
const DNS_WINDOW_SECONDS = 60

export const domainRoutes = new OpenAPIHono()

domainRoutes.post("/o/:slug/admin/domains/add", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const domain = field(body, "domain")?.toLowerCase() ?? null

  // Rejected here as well as in the database so the operator gets "that is not
  // a domain" instead of a generic refusal from a failed CHECK.
  if (!domain || !isValidHostname(domain)) {
    return redirectTo(facts, session.slug, { err: "invalid_input" })
  }

  const result = await callForActor("add_organisation_domain", session.sub, {
    p_org_id: session.org,
    p_domain: domain,
    p_is_primary: false,
  })

  return redirectTo(
    facts,
    session.slug,
    result.ok ? { ok: "domain_added" } : { err: "rejected" },
  )
})

domainRoutes.post("/o/:slug/admin/domains/remove", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const domainId = uuidField(body, "domain_id")
  if (!domainId) return redirectTo(facts, session.slug, { err: "invalid_input" })

  const owned = await findOwnedDomain(session.sub, session.org, domainId)
  if (!owned) return redirectTo(facts, session.slug, { err: "rejected" })

  const result = await callForActor("remove_organisation_domain", session.sub, {
    p_domain_id: domainId,
  })

  return redirectTo(
    facts,
    session.slug,
    result.ok ? { ok: "domain_removed" } : { err: "rejected" },
  )
})

domainRoutes.post("/o/:slug/admin/domains/primary", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const domainId = uuidField(body, "domain_id")
  if (!domainId) return redirectTo(facts, session.slug, { err: "invalid_input" })

  const owned = await findOwnedDomain(session.sub, session.org, domainId)

  // Only a verified domain may become primary. An unverified primary would be
  // used for domain-based discovery on the strength of an unproven claim.
  if (!owned || !owned.verified) {
    return redirectTo(facts, session.slug, { err: "rejected" })
  }

  const result = await callForActor("set_primary_organisation_domain", session.sub, {
    p_domain_id: domainId,
  })

  return redirectTo(
    facts,
    session.slug,
    result.ok ? { ok: "domain_primary" } : { err: "rejected" },
  )
})

domainRoutes.post("/o/:slug/admin/domains/verify", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const domainId = uuidField(body, "domain_id")
  if (!domainId) return redirectTo(facts, session.slug, { err: "invalid_input" })

  // RULE 1: load the row from this org's own list before doing anything with it.
  const owned = await findOwnedDomain(session.sub, session.org, domainId)
  if (!owned) return redirectTo(facts, session.slug, { err: "rejected" })

  if (owned.verified) return redirectTo(facts, session.slug, { ok: "domain_verified" })

  const perClient = rateLimit(
    `dns:client:${clientKey(ctx.req.raw.headers)}`,
    DNS_CLIENT_LIMIT,
    DNS_WINDOW_SECONDS,
  )
  const perDomain = rateLimit(
    `dns:domain:${owned.domain}`,
    DNS_DOMAIN_LIMIT,
    DNS_WINDOW_SECONDS,
  )
  if (!perClient.allowed || !perDomain.allowed) {
    return redirectTo(facts, session.slug, { err: "rate_limited" })
  }

  // The expected value comes from the row the database gave us, never from the
  // form: a caller-supplied token would let anyone declare their own answer.
  const expectedToken = extractToken(owned.dns_record_value)
  if (!expectedToken) return redirectTo(facts, session.slug, { err: "rejected" })

  const outcome = await verifyDomainToken(owned.domain, expectedToken)
  if (!outcome.verified) {
    // Not an error state: "the record is not there yet" is the common case
    // while DNS propagates, and it is reported as information.
    return redirectTo(facts, session.slug, { ok: "domain_unverified" })
  }

  // callRpc, NOT callForActor. This is the one domain RPC that takes no `p_actor_id`: it is
  // service_role-only by design (there is no client-callable path that sets verified = true), and
  // it names its second parameter `p_verified_by`. callForActor appends `p_actor_id` to every
  // call, and PostgREST resolves a function by its ARGUMENT NAMES - so that call named a function
  // signature the database does not have, failed to resolve, and returned "The change was
  // refused" every single time a DNS check succeeded. Domain verification has never completed.
  //
  // It is also what should fill verified_by, which was never being set.
  const result = await callRpc("mark_organisation_domain_verified", {
    p_domain_id: domainId,
    p_verified_by: session.sub,
  })

  return redirectTo(
    facts,
    session.slug,
    result.ok ? { ok: "domain_verified" } : { err: "rejected" },
  )
})

/**
 * Adopt every existing account at a verified domain.
 *
 * The one action here that adds people who did not ask to be added, so it is the
 * one worth reading twice. Its authority is the DNS proof plus org-admin: the
 * same proof the self-service path already trusts to let a matching user skip
 * the queue, used in the opposite direction.
 *
 * VERIFIED IS CHECKED HERE AS WELL AS IN THE RPC, and not as ceremony. Rule 1
 * already loaded the row, so refusing an unverified one costs nothing and turns
 * what would be a generic refusal into a state the page can explain. The RPC
 * re-checks because it is reachable by `authenticated` directly.
 *
 * No DNS probe and no rate limit of its own: nothing here leaves our
 * infrastructure, and the general per-client limiter in `prepare` already
 * covers a form-submission flood. The expensive part is a single scan of
 * auth.users for one domain, inside a row lock the RPC takes.
 */
domainRoutes.post("/o/:slug/admin/domains/add-users", async (ctx) => {
  const prep = await prepare(ctx)
  if (!prep.ok) return prep.response
  const { session, facts, body } = prep.value

  const domainId = uuidField(body, "domain_id")
  if (!domainId) return redirectTo(facts, session.slug, { err: "invalid_input" })

  // RULE 1: the row has to be one of this organisation's own.
  const owned = await findOwnedDomain(session.sub, session.org, domainId)
  if (!owned || !owned.verified) {
    return redirectTo(facts, session.slug, { err: "rejected" })
  }

  const result = await callForActor("add_domain_users_to_organisation", session.sub, {
    p_domain_id: domainId,
  })

  // The count is deliberately NOT carried into the banner. RESULT_MESSAGES is a
  // fixed vocabulary so that nothing caller-controlled can render, and the page
  // that follows this redirect already answers "how many" better than a number
  // in a URL would: the roster is longer and the button's own count has dropped.
  return redirectTo(
    facts,
    session.slug,
    result.ok ? { ok: "domain_users_added" } : { err: "rejected" },
  )
})

/**
 * The domain row, but only if it belongs to the session's organisation.
 *
 * Implemented by listing this org's domains and matching the id, rather than
 * fetching the row by id and comparing its org: there is no by-id read that
 * returns another org's row, so there is nothing to leak even transiently.
 */
async function findOwnedDomain(
  actorId: string,
  orgId: string,
  domainId: string,
): Promise<OrgDomain | null> {
  const domains = await listDomains(actorId, orgId)
  if (!domains.ok) return null
  return domains.data.find((domain) => domain.domain_id === domainId) ?? null
}

/**
 * The bare token from a `boss-org-verification=<token>` record value.
 *
 * list_organisation_domains hands back the full record text because that is
 * what the admin has to paste into DNS; the comparison needs just the token.
 */
export function extractToken(recordValue: string): string | null {
  const prefix = "boss-org-verification="
  if (!recordValue?.startsWith(prefix)) return null
  const token = recordValue.slice(prefix.length).trim()
  return token.length > 0 ? token : null
}
