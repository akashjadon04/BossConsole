/**
 * WebAuthn ceremony verification helpers
 *
 * These are the server-side checks that a browser does *not* perform for us:
 * the relying party is responsible for verifying that the signed
 * `clientDataJSON` really carries the challenge we issued, that
 * `authenticatorData.rpIdHash` is a hash of *our* RP ID, and that the signature
 * counter has not gone backwards (WebAuthn Level 2, §7.1 / §7.2).
 */

import { asBufferSource, decodeBase64Any, decodeBase64AnyToUtf8, encodedValuesMatch } from "./base64.ts"

/** COSE algorithm identifiers we support (IANA COSE Algorithms registry) */
export const COSE_ALG_ES256 = -7
export const COSE_ALG_RS256 = -257

/** Algorithms offered during registration — must all be verifiable below */
export const SUPPORTED_COSE_ALGS: readonly number[] = [COSE_ALG_ES256, COSE_ALG_RS256]

export interface ClientData {
  type: string
  challenge: string
  origin: string
  crossOrigin?: boolean
  [key: string]: unknown
}

export interface ParsedClientData {
  /** Raw bytes exactly as signed by the authenticator — hash *these*, not a re-encoded string */
  bytes: Uint8Array
  /** Parsed JSON view of the same bytes */
  data: ClientData
}

export interface ParsedAuthenticatorData {
  rpIdHash: Uint8Array
  flags: number
  userPresent: boolean
  userVerified: boolean
  attestedCredentialData: boolean
  extensionData: boolean
  signCount: number
  /** Present only when the AT flag is set (registration) */
  credentialId?: Uint8Array
  /** COSE-encoded credential public key, present only when the AT flag is set */
  credentialPublicKey?: Uint8Array
}

/**
 * Decodes and parses `clientDataJSON`.
 *
 * Accepts base64 or base64url, padded or unpadded (see utils/base64.ts).
 *
 * @throws Error when the payload is not decodable or not a JSON object
 */
export function parseClientDataJSON(clientDataJSON: string): ParsedClientData {
  const bytes = decodeBase64Any(clientDataJSON)
  const text = new TextDecoder().decode(bytes)

  let data: unknown
  try {
    data = JSON.parse(text)
  } catch (_error) {
    throw new Error('Invalid clientDataJSON: not valid JSON')
  }

  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('Invalid clientDataJSON: expected a JSON object')
  }

  return { bytes, data: data as ClientData }
}

/**
 * Convenience wrapper used where only the decoded text is needed.
 */
export function decodeClientDataText(clientDataJSON: string): string {
  return decodeBase64AnyToUtf8(clientDataJSON)
}

/**
 * Verifies that the challenge inside the *signed* client data is the challenge
 * this ceremony was issued.
 *
 * This is the check that makes the signature meaningful: without it, the only
 * challenge compared against storage is the copy supplied by the caller
 * alongside the credential, so a captured assertion replays successfully next
 * to any currently-live challenge.
 */
export function challengeMatches(clientDataChallenge: unknown, expectedChallenge: string): boolean {
  if (typeof clientDataChallenge !== 'string') return false
  return encodedValuesMatch(clientDataChallenge, expectedChallenge)
}

/**
 * Parses `authenticatorData` (WebAuthn Level 2, §6.1).
 *
 * Layout: rpIdHash (32) || flags (1) || signCount (4) [|| attestedCredentialData]
 */
export function parseAuthenticatorData(authData: Uint8Array): ParsedAuthenticatorData {
  if (authData.length < 37) {
    throw new Error(`Invalid authenticatorData: expected at least 37 bytes, got ${authData.length}`)
  }

  const rpIdHash = authData.slice(0, 32)
  const flags = authData[32]
  const signCount = ((authData[33] << 24) | (authData[34] << 16) | (authData[35] << 8) | authData[36]) >>> 0

  const parsed: ParsedAuthenticatorData = {
    rpIdHash,
    flags,
    userPresent: (flags & 0x01) !== 0,
    userVerified: (flags & 0x04) !== 0,
    attestedCredentialData: (flags & 0x40) !== 0,
    extensionData: (flags & 0x80) !== 0,
    signCount
  }

  if (parsed.attestedCredentialData) {
    // aaguid (16) || credentialIdLength (2) || credentialId || credentialPublicKey
    let offset = 37 + 16
    if (authData.length < offset + 2) {
      throw new Error('Invalid authenticatorData: truncated attested credential data')
    }
    const credIdLength = (authData[offset] << 8) | authData[offset + 1]
    offset += 2

    // The spec requires 16-1023 bytes. Rejecting 0 matters beyond tidiness: an
    // empty Uint8Array is truthy, so a zero-length id would encode to "" and slip
    // past the `attested.credentialId && …` binding in the registration service,
    // letting the caller store an arbitrary credential id of its own choosing.
    if (credIdLength === 0) {
      throw new Error('Invalid authenticatorData: zero-length credential id')
    }

    if (authData.length < offset + credIdLength) {
      throw new Error('Invalid authenticatorData: truncated credential id')
    }
    parsed.credentialId = authData.slice(offset, offset + credIdLength)
    offset += credIdLength

    parsed.credentialPublicKey = authData.slice(offset)
  }

  return parsed
}

/**
 * Parses `authenticatorData` from its base64/base64url transport encoding.
 */
export function parseAuthenticatorDataBase64(authenticatorData: string): ParsedAuthenticatorData {
  return parseAuthenticatorData(decodeBase64Any(authenticatorData))
}

/**
 * SHA-256 over bytes, returned as bytes.
 */
export async function sha256Bytes(data: Uint8Array): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest('SHA-256', asBufferSource(data))
  return new Uint8Array(digest)
}

/**
 * SHA-256 of an RP ID string (what authenticators put in `authData.rpIdHash`).
 */
export function rpIdHash(rpId: string): Promise<Uint8Array> {
  return sha256Bytes(new TextEncoder().encode(rpId))
}

function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) {
    diff |= a[i] ^ b[i]
  }
  return diff === 0
}

/**
 * Finds which of `candidateRpIds` the authenticator signed for.
 *
 * @returns the matching RP ID, or `null` when the assertion/attestation was
 *          produced for a relying party that is not ours
 */
export async function matchRpIdHash(
  hash: Uint8Array,
  candidateRpIds: readonly string[]
): Promise<string | null> {
  if (hash.length !== 32) return null

  for (const candidate of candidateRpIds) {
    if (!candidate) continue
    const expected = await rpIdHash(candidate)
    if (bytesEqual(hash, expected)) {
      return candidate
    }
  }
  return null
}

export interface SignCounterVerdict {
  /** false when the counter went backwards (or failed to advance) */
  ok: boolean
  /** true when both sides report 0 — this authenticator does not keep a counter */
  counterUnsupported: boolean
  /** value to persist, or null when there is nothing to update */
  nextValue: number | null
  reason?: string
}

/**
 * Applies the WebAuthn signature counter rule (Level 2, §7.2 step 21).
 *
 * Authenticators that do not implement a counter report 0 forever — Apple's
 * platform authenticator is the common example — so "0 now and 0 stored" must
 * be accepted rather than treated as a clone. Any other non-increase is treated
 * as a possible cloned authenticator and rejected.
 */
export function evaluateSignCounter(
  storedCount: number | null | undefined,
  receivedCount: number
): SignCounterVerdict {
  const stored = typeof storedCount === 'number' && Number.isFinite(storedCount) && storedCount > 0
    ? storedCount
    : 0

  if (receivedCount === 0 && stored === 0) {
    // Authenticator does not maintain a counter — nothing to compare or store
    return { ok: true, counterUnsupported: true, nextValue: null }
  }

  if (receivedCount <= stored) {
    return {
      ok: false,
      counterUnsupported: false,
      nextValue: null,
      reason: `Signature counter did not increase (stored ${stored}, received ${receivedCount})`
    }
  }

  return { ok: true, counterUnsupported: false, nextValue: receivedCount }
}
