/**
 * Organisation Edge Function - routing.
 *
 * Kept apart from index.ts so tests can drive `app.request()` without binding a
 * listener, matching redirect / crash-report / latest-release.
 *
 * NO CORS MIDDLEWARE, DELIBERATELY. Every route here is a same-origin,
 * cookie-authenticated HTML page. A `credentials: true` CORS policy is exactly
 * how you convert one of those into a cross-origin data leak, and there is no
 * caller that needs it: the desktop app opens these pages in a browser tab, it
 * does not fetch them. If a future caller genuinely needs cross-origin access,
 * give it a JSON route with its own bearer auth rather than opening this up.
 *
 * `OpenAPIHono` with plain `app.get`/`app.post` and no `createRoute`, matching
 * redirect and crash-report, which instantiate it and never touch the OpenAPI
 * half. There is no swagger UI here on purpose: these are authenticated admin
 * pages, and the neighbouring plugin-store's swagger route is precisely the
 * same-origin third-party-script problem that makes the CSRF design necessary.
 *
 * HTML FROM AN EDGE FUNCTION ONLY RENDERS ON A CUSTOM DOMAIN. Supabase rewrites
 * `text/html` to `text/plain` on the *.supabase.co hostname, so every URL the
 * desktop plugin builds must come from SUPABASE_FUNCTION_URL
 * (api.risaboss.com), never from the project-ref host.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { MissingSessionSecretError } from "./utils/config.ts"
import { htmlResponse, jsonResponse } from "./utils/responses.ts"
import { errorPage } from "./views/error.ts"
import { orgPageRoutes } from "./routes/org-page.ts"
import { adminPageRoutes } from "./routes/admin-page.ts"
import { adminActionRoutes } from "./routes/admin-actions.ts"
import { domainRoutes } from "./routes/domains.ts"
import { joinRoutes } from "./routes/join.ts"
import { pluginPageRoutes } from "./routes/plugin-page.ts"

export const app = new OpenAPIHono().basePath("/organisation")

// The admin tree is split across two modules, and their paths are disjoint:
// domainRoutes owns /admin/domains/* and adminActionRoutes owns the rest. No
// route here is a prefix or wildcard of another, so mount order is not load
// bearing. Keep it that way -- adding a catch-all under /admin would make it so.
app.route("/", orgPageRoutes)
app.route("/", adminPageRoutes)
app.route("/", domainRoutes)
app.route("/", adminActionRoutes)
app.route("/", joinRoutes)
app.route("/", pluginPageRoutes)

/**
 * Liveness only. It deliberately does NOT report whether ORG_SESSION_SECRET is
 * configured: that is a fact about our deployment posture, and an unauthenticated
 * caller has no business learning it. A misconfigured deployment announces
 * itself through the 503 on the first real page instead.
 */
app.get("/health", () => jsonResponse({ status: "healthy" }))

app.notFound(() =>
  htmlResponse(
    (nonce) =>
      errorPage({
        nonce,
        title: "Not found - BOSS",
        heading: "Not found",
        message: "There is nothing at this address.",
      }),
    { status: 404 },
  )
)

/**
 * The last line of defence.
 *
 * A missing session secret becomes a 503, because the function cannot
 * authenticate anybody and pretending otherwise is worse than being down.
 * Everything else becomes a generic 500: `err.message` is not rendered, since
 * an unexpected error's message routinely carries a query fragment, a hostname
 * or a key name.
 */
app.onError((err, _ctx) => {
  if (err instanceof MissingSessionSecretError) {
    console.error("ORG_SESSION_SECRET is not configured - refusing to serve")
    return htmlResponse(
      (nonce) =>
        errorPage({
          nonce,
          title: "Unavailable - BOSS",
          heading: "Temporarily unavailable",
          message: "This service is not configured. Please try again later.",
        }),
      { status: 503 },
    )
  }

  console.error("unhandled error:", err)
  return htmlResponse(
    (nonce) =>
      errorPage({
        nonce,
        title: "Something went wrong - BOSS",
        heading: "Something went wrong",
        message: "Please try again. If it keeps happening, reopen the page from BOSS.",
      }),
    { status: 500 },
  )
})
