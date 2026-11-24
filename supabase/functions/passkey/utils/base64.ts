/**
 * Base64 / base64url helpers
 *
 * WebAuthn payloads are transported as **base64url** (RFC 4648 §5), usually
 * without padding: that is what `navigator.credentials.*` results look like
 * once passed through the reference web client, and what native clients send.
 *
 * `atob()` and `@std/encoding/base64url`'s `decodeBase64Url()` are both strict:
 * - `atob()` rejects `-` and `_`
 * - `decodeBase64Url()` rejects `+` and `/`
 *
 * A payload only contains `+`/`/` (or `-`/`_`) when the underlying bytes happen
 * to produce them, so using the wrong decoder fails for *some* ceremonies and
 * works for others — a data-dependent failure that looks random in production.
 *
 * Everything in this function therefore decodes through `decodeBase64Any()`,
 * which accepts either alphabet with or without padding.
 */

/**
 * Decodes a base64 or base64url string (padded or unpadded) into bytes.
 *
 * Lenient by design about *which* alphabet: `-`/`_` are mapped onto `+`/`/`
 * before decoding, so a payload mixing both (`a-b+c`) is accepted rather than
 * rejected. Clients do not mix alphabets in practice, and being strict about it
 * would buy nothing — the bytes are identical either way.
 *
 * @throws Error when the input carries no data or is not valid base64
 */
export function decodeBase64Any(input: string): Uint8Array {
  if (typeof input !== 'string' || input.length === 0) {
    throw new Error('Invalid base64 input: expected a non-empty string')
  }

  // Strip any whitespace/newlines, then map the base64url alphabet onto base64
  const normalized = input
    .replace(/\s+/g, '')
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .replace(/=+$/, '')

  // A string of nothing but padding/whitespace decodes to zero bytes, which would
  // otherwise slip past the non-empty check on `input` above
  if (normalized.length === 0) {
    throw new Error('Invalid base64 input: no data')
  }

  if (!/^[A-Za-z0-9+/]*$/.test(normalized)) {
    throw new Error('Invalid base64 input: unexpected characters')
  }

  const remainder = normalized.length % 4
  if (remainder === 1) {
    throw new Error('Invalid base64 input: truncated')
  }

  const padded = remainder === 0 ? normalized : normalized + '='.repeat(4 - remainder)

  // atob is safe here: the string has been normalized to the standard alphabet
  const binary = atob(padded)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

/**
 * Decodes a base64/base64url string and interprets the bytes as UTF-8 text.
 *
 * Note this is *not* the same as `atob()`: `atob()` yields one JS char per byte
 * (latin-1), which corrupts any multi-byte UTF-8 sequence in the payload.
 */
export function decodeBase64AnyToUtf8(input: string): string {
  return new TextDecoder().decode(decodeBase64Any(input))
}

/**
 * Encodes bytes as unpadded base64url.
 */
export function encodeBase64UrlBytes(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

/**
 * Canonicalises a base64/base64url string so two encodings of the same bytes
 * compare equal: base64url alphabet, no padding, no whitespace.
 *
 * Used for challenge comparison, where the value round-trips through an
 * authenticator and different clients pad differently.
 */
export function normalizeBase64Url(value: string): string {
  if (typeof value !== 'string') return ''
  return value
    .replace(/\s+/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

/**
 * Presents bytes as the `BufferSource` shape Web Crypto's typings expect.
 *
 * A `Uint8Array` already is a valid `BufferSource` at runtime; the cast exists
 * only to bridge the `ArrayBufferLike` / `ArrayBuffer` distinction in Deno's
 * lib types, which otherwise rejects every `crypto.subtle` call.
 */
export function asBufferSource(bytes: Uint8Array): BufferSource {
  return bytes as unknown as BufferSource
}

/**
 * Compares two encoded values for equality after canonicalisation, in
 * (approximately) constant time with respect to the compared content.
 */
export function encodedValuesMatch(a: string, b: string): boolean {
  const left = normalizeBase64Url(a)
  const right = normalizeBase64Url(b)

  if (left.length === 0 || right.length === 0) return false
  if (left.length !== right.length) return false

  let diff = 0
  for (let i = 0; i < left.length; i++) {
    diff |= left.charCodeAt(i) ^ right.charCodeAt(i)
  }
  return diff === 0
}
