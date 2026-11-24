/**
 * One page of a list, and the numbers a pager needs to describe it.
 *
 * WHERE THE SLICING HAPPENS, so nobody assumes more than exists: here, in the function, after the
 * RPC has returned every row. `list_organisation_members` and `list_org_plugins` have no LIMIT and
 * this does not give them one - the whole list still crosses the wire. What paging buys is a PAGE
 * a person can read instead of a table hundreds of rows long, which is the problem actually being
 * solved; it is not a fix for a large query, and adding one later means changing the RPCs rather
 * than this file.
 *
 * The page number is CLAMPED rather than validated. It arrives from a query string, so it can be
 * anything at all - a word, a negative, a page past the end after somebody is removed from the
 * organisation. Every one of those should show a readable page rather than an error or an empty
 * table, and clamping is what makes a stale bookmark land on the last page instead of nothing.
 */
export interface Paged<T> {
  items: T[]
  /** The page actually shown, after clamping. 1-based. */
  page: number
  /** Total pages; at least 1, so an empty list is "page 1 of 1" rather than "1 of 0". */
  pages: number
  total: number
  /** 1-based inclusive range of this page, for "showing 1 to 25 of 120". Both 0 when empty. */
  from: number
  to: number
}

export function paginate<T>(items: readonly T[], requested: number, size: number): Paged<T> {
  const total = items.length
  const pages = Math.max(1, Math.ceil(total / size))
  const page = Math.min(Math.max(Number.isFinite(requested) ? Math.floor(requested) : 1, 1), pages)
  const start = (page - 1) * size
  const slice = items.slice(start, start + size)
  return {
    items: slice,
    page,
    pages,
    total,
    from: total === 0 ? 0 : start + 1,
    to: total === 0 ? 0 : start + slice.length,
  }
}

/**
 * A page number from a query string, or 1.
 *
 * Anything unreadable becomes 1 rather than an error: `?members=` is what a browser sends for an
 * empty field, and `?members=abc` is what a hand-edited URL sends. Out-of-range values are left
 * alone here and clamped by [paginate], which is the only place that knows how many pages exist.
 */
export function pageParam(raw: string | null): number {
  if (!raw) return 1
  const parsed = Number.parseInt(raw, 10)
  return Number.isFinite(parsed) && parsed >= 1 ? parsed : 1
}
