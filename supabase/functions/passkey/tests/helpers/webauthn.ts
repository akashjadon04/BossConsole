/**
 * WebAuthn test helpers
 *
 * Builds real attestation objects, authenticator data and signatures with the
 * Web Crypto API, so verification tests exercise the production code paths
 * instead of asserting against stubs.
 */

import { encodeBase64Url } from "@std/encoding/base64url"
import { COSE_ALG_ES256, COSE_ALG_RS256 } from "../../utils/webauthn.ts"
import { asBufferSource } from "../../utils/base64.ts"

export const TEST_RP_ID = 'api.risaboss.com'
export const TEST_ORIGIN = 'https://api.risaboss.com'

/** Transport encoding to use for a credential payload */
export type PayloadEncoding = 'base64url' | 'base64'

export function encodePayload(bytes: Uint8Array, encoding: PayloadEncoding = 'base64url'): string {
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  const standard = btoa(binary)
  if (encoding === 'base64') return standard
  return standard.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

// ============================================================================
// Minimal CBOR encoder (enough for COSE keys and attestation objects)
// ============================================================================

function cborHead(major: number, value: number): number[] {
  if (value < 24) return [(major << 5) | value]
  if (value < 0x100) return [(major << 5) | 24, value]
  if (value < 0x10000) return [(major << 5) | 25, (value >> 8) & 0xff, value & 0xff]
  return [
    (major << 5) | 26,
    (value >>> 24) & 0xff,
    (value >>> 16) & 0xff,
    (value >>> 8) & 0xff,
    value & 0xff
  ]
}

function cborInt(value: number): number[] {
  return value >= 0 ? cborHead(0, value) : cborHead(1, -1 - value)
}

function cborBytes(bytes: Uint8Array): number[] {
  return [...cborHead(2, bytes.length), ...Array.from(bytes)]
}

function cborText(text: string): number[] {
  const bytes = new TextEncoder().encode(text)
  return [...cborHead(3, bytes.length), ...Array.from(bytes)]
}

function cborMap(entries: Array<[number[], number[]]>): Uint8Array {
  const out: number[] = [...cborHead(5, entries.length)]
  for (const [key, value] of entries) {
    out.push(...key, ...value)
  }
  return new Uint8Array(out)
}

// ============================================================================
// COSE keys
// ============================================================================

export function coseKeyES256(x: Uint8Array, y: Uint8Array): Uint8Array {
  return cborMap([
    [cborInt(1), cborInt(2)],            // kty: EC2
    [cborInt(3), cborInt(COSE_ALG_ES256)], // alg: ES256
    [cborInt(-1), cborInt(1)],           // crv: P-256
    [cborInt(-2), cborBytes(x)],         // x
    [cborInt(-3), cborBytes(y)]          // y
  ])
}

export function coseKeyRS256(n: Uint8Array, e: Uint8Array): Uint8Array {
  return cborMap([
    [cborInt(1), cborInt(3)],              // kty: RSA
    [cborInt(3), cborInt(COSE_ALG_RS256)], // alg: RS256
    [cborInt(-1), cborBytes(n)],           // n
    [cborInt(-2), cborBytes(e)]            // e
  ])
}

async function coseKeyFor(publicKey: CryptoKey, alg: number): Promise<Uint8Array> {
  if (alg === COSE_ALG_ES256) {
    const raw = new Uint8Array(await crypto.subtle.exportKey('raw', publicKey))
    return coseKeyES256(raw.slice(1, 33), raw.slice(33, 65))
  }

  const jwk = await crypto.subtle.exportKey('jwk', publicKey)
  return coseKeyRS256(decodeB64Url(jwk.n!), decodeB64Url(jwk.e!))
}

function decodeB64Url(value: string): Uint8Array {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4))
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

// ============================================================================
// Authenticator data / attestation objects
// ============================================================================

export async function sha256(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', asBufferSource(data)))
}

export function rpIdHashFor(rpId: string): Promise<Uint8Array> {
  return sha256(new TextEncoder().encode(rpId))
}

export interface AuthDataOptions {
  rpId?: string
  /** Overrides rpId — use to simulate an assertion produced for another RP */
  rpIdHash?: Uint8Array
  signCount?: number
  /** flags byte; defaults to UP+UV (and AT when a credential is attested) */
  flags?: number
  credentialId?: Uint8Array
  coseKey?: Uint8Array
}

export async function buildAuthenticatorData(options: AuthDataOptions = {}): Promise<Uint8Array> {
  const attested = Boolean(options.credentialId && options.coseKey)
  const hash = options.rpIdHash ?? await rpIdHashFor(options.rpId ?? TEST_RP_ID)
  const flags = options.flags ?? (attested ? 0x45 : 0x05)
  const signCount = options.signCount ?? 0

  const head = [
    ...Array.from(hash),
    flags,
    (signCount >>> 24) & 0xff,
    (signCount >>> 16) & 0xff,
    (signCount >>> 8) & 0xff,
    signCount & 0xff
  ]

  if (!attested) return new Uint8Array(head)

  const credentialId = options.credentialId!
  return new Uint8Array([
    ...head,
    ...new Array(16).fill(0x00), // aaguid
    (credentialId.length >> 8) & 0xff,
    credentialId.length & 0xff,
    ...Array.from(credentialId),
    ...Array.from(options.coseKey!)
  ])
}

/** Attestation object with fmt "none", as produced for attestation: "none" */
export function buildAttestationObject(authData: Uint8Array): Uint8Array {
  return cborMap([
    [cborText('fmt'), cborText('none')],
    [cborText('attStmt'), Array.from(cborMap([]))],
    [cborText('authData'), cborBytes(authData)]
  ])
}

// ============================================================================
// Credentials
// ============================================================================

export async function generateKeyPair(alg: number): Promise<CryptoKeyPair> {
  if (alg === COSE_ALG_RS256) {
    return await crypto.subtle.generateKey(
      {
        name: 'RSASSA-PKCS1-v1_5',
        modulusLength: 2048,
        publicExponent: new Uint8Array([0x01, 0x00, 0x01]),
        hash: 'SHA-256'
      },
      true,
      ['sign', 'verify']
    ) as CryptoKeyPair
  }

  return await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  ) as CryptoKeyPair
}

/** The value the server stores in user_passkeys.public_key for this key */
export async function storedPublicKey(publicKey: CryptoKey, alg: number): Promise<string> {
  if (alg === COSE_ALG_RS256) {
    const spki = new Uint8Array(await crypto.subtle.exportKey('spki', publicKey))
    return encodeBase64Url(spki)
  }
  const raw = new Uint8Array(await crypto.subtle.exportKey('raw', publicKey))
  return encodeBase64Url(raw)
}

export interface ClientDataOptions {
  type: string
  challenge: string
  origin?: string
  /** Extra members — the spec explicitly allows unknown keys in clientDataJSON */
  extra?: Record<string, unknown>
  encoding?: PayloadEncoding
}

export function buildClientDataJSON(options: ClientDataOptions): { text: string; encoded: string } {
  const text = JSON.stringify({
    type: options.type,
    challenge: options.challenge,
    origin: options.origin ?? TEST_ORIGIN,
    crossOrigin: false,
    ...(options.extra ?? {})
  })
  const encoded = encodePayload(new TextEncoder().encode(text), options.encoding ?? 'base64url')
  return { text, encoded }
}

/**
 * Builds a clientDataJSON whose base64url encoding contains both '-' and '_'
 * (equivalently: whose standard base64 contains '+' and '/').
 *
 * These are the payloads that a decoder locked to one alphabet rejects, which
 * is why the failure used to look data-dependent.
 */
export function buildAlphabetSensitiveClientDataJSON(
  options: ClientDataOptions
): { text: string; encoded: string; standard: string } {
  for (let padding = 0; padding < 4; padding++) {
    const note = '?'.repeat(3) + '>'.repeat(3) + 'x'.repeat(padding)
    const built = buildClientDataJSON({
      ...options,
      extra: { ...(options.extra ?? {}), other_keys_can_be_added_here: note },
      encoding: 'base64url'
    })
    const standard = encodePayload(new TextEncoder().encode(built.text), 'base64')
    if (standard.includes('+') && standard.includes('/')) {
      return { ...built, standard }
    }
  }
  throw new Error('Failed to build a clientDataJSON exercising both base64 alphabets')
}

export interface RegistrationCredentialOptions {
  challenge: string
  alg?: number
  rpId?: string
  rpIdHash?: Uint8Array
  /** Override the flags byte, e.g. to clear User Present */
  flags?: number
  signCount?: number
  origin?: string
  encoding?: PayloadEncoding
  keyPair?: CryptoKeyPair
  credentialId?: Uint8Array
}

export interface TestRegistrationCredential {
  credential: {
    id: string
    rawId: string
    type: string
    response: { clientDataJSON: string; attestationObject: string }
  }
  keyPair: CryptoKeyPair
  alg: number
  credentialId: Uint8Array
  credentialIdBase64Url: string
  /** Value the server is expected to persist as public_key */
  expectedPublicKey: string
}

export async function createRegistrationCredential(
  options: RegistrationCredentialOptions
): Promise<TestRegistrationCredential> {
  const alg = options.alg ?? COSE_ALG_ES256
  const keyPair = options.keyPair ?? await generateKeyPair(alg)
  const credentialId = options.credentialId ?? crypto.getRandomValues(new Uint8Array(16))
  const coseKey = await coseKeyFor(keyPair.publicKey, alg)

  const authData = await buildAuthenticatorData({
    rpId: options.rpId,
    rpIdHash: options.rpIdHash,
    flags: options.flags,
    signCount: options.signCount ?? 0,
    credentialId,
    coseKey
  })

  const { encoded: clientDataJSON } = buildClientDataJSON({
    type: 'webauthn.create',
    challenge: options.challenge,
    origin: options.origin,
    encoding: options.encoding
  })

  const encoding = options.encoding ?? 'base64url'

  return {
    credential: {
      id: encodePayload(credentialId, encoding),
      rawId: encodePayload(credentialId, encoding),
      type: 'public-key',
      response: {
        clientDataJSON,
        attestationObject: encodePayload(buildAttestationObject(authData), encoding)
      }
    },
    keyPair,
    alg,
    credentialId,
    credentialIdBase64Url: encodeBase64Url(credentialId),
    expectedPublicKey: await storedPublicKey(keyPair.publicKey, alg)
  }
}

export interface AssertionOptions {
  challenge: string
  credentialId: Uint8Array
  privateKey: CryptoKey
  alg?: number
  rpId?: string
  rpIdHash?: Uint8Array
  /** Override the flags byte, e.g. to clear User Present */
  flags?: number
  signCount?: number
  origin?: string
  encoding?: PayloadEncoding
  /** Overrides the challenge written into clientDataJSON (for mismatch tests) */
  clientDataChallenge?: string
  /** Pre-built clientDataJSON (already transport encoded) */
  clientDataJSON?: { text: string; encoded: string }
}

export interface TestAssertionCredential {
  id: string
  rawId: string
  type: string
  response: {
    clientDataJSON: string
    authenticatorData: string
    signature: string
    userHandle?: string
  }
}

export async function createAssertion(options: AssertionOptions): Promise<TestAssertionCredential> {
  const alg = options.alg ?? COSE_ALG_ES256
  const encoding = options.encoding ?? 'base64url'

  const authenticatorData = await buildAuthenticatorData({
    rpId: options.rpId,
    rpIdHash: options.rpIdHash,
    flags: options.flags,
    signCount: options.signCount ?? 1
  })

  const clientData = options.clientDataJSON ?? buildClientDataJSON({
    type: 'webauthn.get',
    challenge: options.clientDataChallenge ?? options.challenge,
    origin: options.origin,
    encoding
  })

  const clientDataBytes = new TextEncoder().encode(clientData.text)
  const clientDataHash = await sha256(clientDataBytes)
  const signedData = new Uint8Array([...authenticatorData, ...clientDataHash])

  const rawSignature = new Uint8Array(await crypto.subtle.sign(
    alg === COSE_ALG_RS256
      ? { name: 'RSASSA-PKCS1-v1_5' }
      : { name: 'ECDSA', hash: 'SHA-256' },
    options.privateKey,
    asBufferSource(signedData)
  ))

  // ES256 assertions are DER-encoded on the wire; RSA signatures are raw
  const signature = alg === COSE_ALG_RS256 ? rawSignature : rawToDERSignature(rawSignature)

  return {
    // Encoded with the same alphabet as the rest of the payload: a client using
    // a standard-base64 encoder emits the credential id that way too, and the
    // server has to resolve it to the same stored credential.
    id: encodePayload(options.credentialId, encoding),
    rawId: encodePayload(options.credentialId, encoding),
    type: 'public-key',
    response: {
      clientDataJSON: clientData.encoded,
      authenticatorData: encodePayload(authenticatorData, encoding),
      signature: encodePayload(signature, encoding),
      userHandle: undefined
    }
  }
}

/**
 * Converts a raw ECDSA signature (r || s) to the DER encoding authenticators emit.
 */
export function rawToDERSignature(rawSignature: Uint8Array): Uint8Array {
  const r = rawSignature.slice(0, 32)
  const s = rawSignature.slice(32, 64)

  let rStart = 0
  while (rStart < r.length - 1 && r[rStart] === 0) rStart++
  const rBytes = r.slice(rStart)

  let sStart = 0
  while (sStart < s.length - 1 && s[sStart] === 0) sStart++
  const sBytes = s.slice(sStart)

  const rNeedsPadding = (rBytes[0] & 0x80) !== 0
  const sNeedsPadding = (sBytes[0] & 0x80) !== 0

  const rLength = rBytes.length + (rNeedsPadding ? 1 : 0)
  const sLength = sBytes.length + (sNeedsPadding ? 1 : 0)

  const totalLength = 2 + rLength + 2 + sLength
  const der = new Uint8Array(2 + totalLength)

  let offset = 0
  der[offset++] = 0x30
  der[offset++] = totalLength
  der[offset++] = 0x02
  der[offset++] = rLength
  if (rNeedsPadding) der[offset++] = 0x00
  der.set(rBytes, offset)
  offset += rBytes.length
  der[offset++] = 0x02
  der[offset++] = sLength
  if (sNeedsPadding) der[offset++] = 0x00
  der.set(sBytes, offset)

  return der
}
