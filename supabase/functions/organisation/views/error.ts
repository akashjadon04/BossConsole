/**
 * Error pages.
 *
 * Every failure the browser can reach renders through here, and they all say
 * roughly the same thing on purpose. A page that distinguishes "no session"
 * from "not a member of this organisation" from "no such organisation" tells an
 * unauthenticated visitor which slugs exist and who belongs to them.
 */

import { esc } from "../utils/html.ts"
import { layout } from "./layout.ts"

export interface ErrorPageOptions {
  nonce: string
  title: string
  heading: string
  message: string
  /** Optional call to action, already a safe path. */
  action?: { href: string; label: string } | null
}

export function errorPage(
  { nonce, title, heading, message, action }: ErrorPageOptions,
): string {
  const cta = action
    ? `<p class="spaced"><a href="${esc(action.href)}">${
      esc(action.label)
    }</a></p>`
    : ""

  return layout({
    title,
    nonce,
    body: `
<header class="page"><h1>${esc(heading)}</h1></header>
<section class="card">
  <p>${esc(message)}</p>
  ${cta}
</section>`,
  })
}

/**
 * The one message used for every "you cannot see this" case.
 *
 * Deliberately identical whether the organisation does not exist, the viewer is
 * not a member, or their session expired, and it is the same wording the
 * database returns for the same situations.
 */
export const NOT_AVAILABLE_MESSAGE =
  "This organisation is not available. It may not exist, or you may not have access to it."

/** Shown when there is no usable session, which is also the expired case. */
export const SESSION_EXPIRED_MESSAGE =
  "Your session has expired. Open this page again from the Organisation panel in BOSS."
