/**
 * Live authority probes.
 *
 * The session cookie says WHO the browser is. It never says what they may do.
 * Every request that renders admin state or mutates anything re-asks the
 * database, so that removing someone's admin role takes effect on their next
 * click rather than in up to 30 minutes when their cookie expires.
 *
 * This is the whole reason the cookie can be stateless: there is no privilege
 * cached in it to go stale.
 *
 * H1, restated because it is the single easiest mistake to make here:
 * `authorize('organisation.admin')` is ORG-BLIND. It answers "does this user
 * hold the permission anywhere" and short-circuits true for global admins. It
 * must never gate an org-scoped decision. The gate is always
 * user_is_org_admin(user, org) / user_is_org_member(user, org).
 */

import { serviceClient } from "../utils/org-rpc.ts"

/**
 * Is this user an admin OF THIS ORG?
 *
 * Fails CLOSED: a transport error, a renamed function or a non-boolean answer
 * all return false. The cost of a false negative is an admin seeing a "not
 * found" page during an outage; the cost of a false positive is an outsider
 * editing an organisation.
 */
export function isOrgAdmin(userId: string, orgId: string): Promise<boolean> {
  return probe("user_is_org_admin", userId, orgId)
}

/** Is this user an active member of this org? Fails closed, as above. */
export function isOrgMember(userId: string, orgId: string): Promise<boolean> {
  return probe("user_is_org_member", userId, orgId)
}

/**
 * These two RPCs return a bare boolean rather than the {success, data}
 * envelope every other org RPC uses, so they go direct rather than through
 * callRpc. `data === true` is an identity check, not a truthiness one: a
 * transport-level oddity that yields the string "false" must not read as yes.
 */
async function probe(fn: string, userId: string, orgId: string): Promise<boolean> {
  const { data, error } = await serviceClient().rpc(fn, {
    p_user_id: userId,
    p_org_id: orgId,
  })

  if (error) {
    console.error(`authority probe ${fn} failed:`, error.message)
    return false
  }
  return data === true
}
