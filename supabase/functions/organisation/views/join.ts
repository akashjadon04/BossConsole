/**
 * The invite landing page.
 *
 * THIS PAGE DOES NOT REDEEM THE INVITE. redeem_organisation_invite is
 * `authenticated`-only, so the desktop app is the thing that redeems; this page
 * only shows which organisation the link is for and bounces into
 * `boss://organisation/join?token=...`.
 *
 * That split is worth stating plainly, because folding redemption in here would
 * look simpler and break two things. First, an email prefetch -- Outlook
 * SafeLinks, a scanner, a chat client unfurling the URL -- would consume the
 * invite before a human ever clicked it. Keeping redemption behind an
 * authenticated app action makes a prefetch harmless. Second, the response must
 * be identical for unknown, expired, revoked and exhausted links, or the
 * endpoint becomes an invite oracle; a redeeming page cannot keep that property
 * because success and failure necessarily differ.
 */

import { esc } from "../utils/html.ts"
import { layout } from "./layout.ts"

export interface JoinPageOptions {
  nonce: string
  orgName: string
  orgSlug: string
  description: string | null
  /** `boss://organisation/join?token=...`, already built and validated. */
  deepLink: string
}

export function joinPage(
  { nonce, orgName, orgSlug, description, deepLink }: JoinPageOptions,
): string {
  return layout({
    title: `Join ${orgName} - BOSS`,
    nonce,
    body: `
<header class="page">
  <h1>Join ${esc(orgName)}</h1>
  <span class="slug">@${esc(orgSlug)}</span>
</header>
<p class="sub">${esc(description ?? "")}</p>

<section class="card">
  <p>Open BOSS to accept this invitation.</p>
  <p class="spaced">
    <!-- attrUrl already escaped this; esc() again would double-encode the & of a second
       query parameter and break the link. -->
    <a href="${deepLink}"><button type="button">Open in BOSS</button></a>
  </p>
  <p class="hint spaced">
    Nothing happens until you accept it in the app, so this link is safe to open more than once.
  </p>
</section>`,
  })
}

/**
 * Shown for unknown, expired, revoked and exhausted links alike.
 *
 * One page, one message. Distinguishing them would let someone walk the token
 * space and learn which guesses were once real.
 */
export function invalidInvitePage(nonce: string): string {
  return layout({
    title: "Invitation unavailable - BOSS",
    nonce,
    body: `
<header class="page"><h1>Invitation unavailable</h1></header>
<section class="card">
  <p>This invitation link is not valid. It may have expired, been used up, or been revoked.</p>
  <p class="hint tight">Ask whoever invited you for a fresh link.</p>
</section>`,
  })
}
