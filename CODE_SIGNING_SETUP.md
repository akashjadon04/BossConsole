# Code Signing Setup for BOSS Kotlin Application

This document outlines how to set up code signing certificates and configure GitHub repository secrets for production-ready distribution builds.

## macOS Code Signing Setup

### Prerequisites
1. **Apple Developer Account** with Developer ID Application certificate
2. **Apple ID App-Specific Password** for notarization

### Required GitHub Secrets

#### `MACOS_P12_CERTIFICATE`
- **Format**: Base64-encoded P12 certificate file
- **Description**: Your Developer ID Application certificate exported from Keychain Access
- **How to generate**:
  1. Open Keychain Access on macOS
  2. Find your "Developer ID Application" certificate
  3. Right-click → Export → Save as .p12 file
  4. Convert to base64: `base64 -i certificate.p12 | pbcopy`
  5. Paste the base64 string into this secret

#### `MACOS_P12_PASSWORD`
- **Format**: Plain text password
- **Description**: Password used when exporting the P12 certificate

#### `MACOS_DEVELOPER_ID`
- **Format**: Certificate common name
- **Example**: `"Developer ID Application: Your Name (TEAMID1234)"`
- **How to find**: Check the certificate details in Keychain Access
- **Local builds**: also accepted from `local.properties` (`MACOS_DEVELOPER_ID=...`); when unset, packaging is unsigned

#### `MACOS_APPLE_ID`
- **Format**: Apple ID email
- **Description**: Your Apple Developer account email

#### `MACOS_APP_PASSWORD`
- **Format**: App-specific password
- **Description**: Generate at https://appleid.apple.com/account/manage
- **How to generate**:
  1. Sign in to https://appleid.apple.com/account/manage
  2. Go to "App-Specific Passwords"
  3. Generate new password for "BOSS Notarization"

#### `MACOS_TEAM_ID`
- **Format**: 10-character team identifier
- **Example**: `"TEAMID1234"`
- **How to find**: Apple Developer account → Membership details

#### `MACOS_PROVISIONING_PROFILE_B64`
- **Format**: Base64-encoded `.provisionprofile` file
- **Description**: Apple Developer ID provisioning profile for the branded
  browser's Touch ID keychain entitlement. Not committed to the repo - the
  chromium-branding workflow restores it from this secret. Optional: without
  it, Touch ID signing is skipped.
- **How to generate**: `base64 -i boss-browser.provisionprofile | pbcopy`

## Windows Code Signing Setup

Windows installers are signed with DigiCert Software Trust Manager (KeyLocker)
via the `digicert/ssm-code-signing` action, in both `release.yml` and
`release-lite.yml`.

### Prerequisites
1. **DigiCert KeyLocker account** with a code signing certificate
2. **KeyLocker API key** and **client authentication certificate** (PKCS#12)

### Required GitHub Secrets

The secret names below are the ones the workflows actually read. All five must
be present; if `CODE_SIGNING_CERT_HOST` is empty the workflows skip signing and
build an unsigned MSI.

#### `CODE_SIGNING_CERT_HOST`
- **Format**: Hostname
- **Example**: `clientauth.one.digicert.com`
- **Description**: KeyLocker endpoint, passed to the client tools as `SM_HOST`

#### `CODE_SIGNING_CERT_HOST_API_KEY`
- **Format**: API key string
- **Description**: KeyLocker API key (`SM_API_KEY`)
- **How to generate**: DigiCert ONE console → Access → API Tokens

#### `CODE_SIGNING_CLIENT_CERT`
- **Format**: Base64-encoded PKCS#12 (`.p12`) file
- **Description**: KeyLocker client authentication certificate. The workflow
  decodes it to `Certificate_pkcs12.p12` and passes it as `SM_CLIENT_CERT_FILE`.
- **How to generate** (macOS): `base64 -i Certificate_pkcs12.p12 | pbcopy`
- **How to generate** (Windows):
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes('Certificate_pkcs12.p12')) | Set-Clipboard`

#### `CODE_SIGNING_CLIENT_CERT_PASSWORD`
- **Format**: Plain text password
- **Description**: Password for the client authentication certificate
  (`SM_CLIENT_CERT_PASSWORD`)

#### `CODE_SIGNING_KEYPAIR_ALIAS`
- **Format**: Keypair alias string
- **Description**: Alias of the signing keypair in KeyLocker, passed to
  `smctl sign --keypair-alias`

### `certsync` is not optional, and `smctl sign` lies about failing

`smctl sign` shells out to `signtool`, which looks for the certificate in the
**Windows certificate store**. Nothing puts it there for you, so every signing
job has to run this first:

```
smctl windows certsync --keypair-alias="<alias>"
```

Without it `smctl` prints

```
signCommand command for file <path> FAILED
```

and **exits 0**. That combination is why the x64 MSI came out unsigned in every
release from the day the secrets were added (2026-07-21) to 9.4.24 while the
`Sign Windows MSI with DigiCert KeyLocker` step reported success: nothing in the
job looked at the file, and the exit code said everything was fine.

Two consequences worth keeping:

- **Never trust the sign step's exit code.** `Verify MSI Signature` reads
  `Get-AuthenticodeSignature` off the artifact and is the only authoritative
  answer. It is what caught this.
- **`smctl` gives no reason for a signing failure**, so the signing jobs also run
  `smctl healthcheck`, `smctl windows ksp list` and `smctl keypair ls` next to
  certsync. When signing breaks again, the run says whether the credential, the
  KSP registration or the keypair alias is at fault rather than costing a release
  to find out.

### Shipping a release when signing is broken

`Verify MSI Signature` fails the release rather than publish an unsigned
installer, which is right, but it means broken signing blocks every release. The
deliberate way out is the `allow_unsigned_windows` workflow input on both
`🚀 Release Build` and `🚀 BOSS Lite Release`:

- **Off by default**, and it fails closed: a push-triggered release has no
  `inputs` context at all, so the gate stays a gate. Only an explicit
  `workflow_dispatch` (or a `workflow_call` that passes it) can set it.
- It downgrades the hard failure to a workflow warning. It does not skip signing:
  certsync and the sign attempt still run, so the run still carries the
  diagnostics that say why signing failed.
- **The release says so.** `create-release` reads the signature status the verify
  steps measured and prepends a notice to the release body naming which installer
  is unsigned and what its status was. That is driven by the measurement, not by
  the input, so it also fires for a build with no signing secrets configured, and
  it stays silent when the override was requested but signing actually worked.

The error message on a blocked release names the input, so nobody has to find
this page first.

### What gets signed

| Artifact | Job | Signed |
|----------|-----|--------|
| `BOSS-<version>.msi` (x64) | `build-windows` | Yes. The job fails if credentials were present and the MSI comes out `NotSigned` or `HashMismatch`; any other non-`Valid` status is a warning |
| `BOSS-<version>.msi` (arm64) | `build-windows-arm64` | Best effort, see below |
| Bundled branded Chromium binaries (Windows) | `build-chromium-branding` | No, `signCommand` is stripped unconditionally on the Windows legs |
| `BOSS.exe` inside the MSI | jpackage | No |

The MSI is signed as a container, so SmartScreen is satisfied at install time,
but the binaries inside the installed tree carry no signature of their own.

**Windows ARM64 is best effort.** The DigiCert client tools are x64 binaries and
`signtool` comes from the x86 Windows Kits directory, so both run under emulation
on the `windows-11-arm` runner. Every signing step in that job is
`continue-on-error: true`: if the tooling cannot run, the job still uploads the
unsigned MSI instead of dropping the ARM64 asset from the release. The
`Verify MSI Signature (Windows ARM64)` step then emits a workflow warning and
writes the signature status into the run summary, so an unsigned ARM64 build is
visible rather than silent on a job that is green by design.

## Setting Up GitHub Secrets

1. Go to your GitHub repository
2. Navigate to Settings → Secrets and variables → Actions
3. Click "New repository secret"
4. Add each secret listed above with exact names

## Verification

### Local Testing (macOS)
```bash
# Test certificate availability
security find-identity -v -p codesigning

# Test build with signing
./gradlew packageDmg
```

### Local Testing (Windows)
```bash
# Test DigiCert KeyLocker connection
smctl healthcheck

# Confirm the KSP is registered and the API key can reach the keypair
smctl windows ksp list
smctl keypair ls

# Put the certificate in the Windows store. Signing fails without this, and
# fails by printing FAILED and exiting 0.
smctl windows certsync --keypair-alias="<alias>"

# Build, then sign the MSI the same way CI does
./gradlew packageMsi
smctl sign --keypair-alias "<alias>" --input "composeApp/build/compose/binaries/main/msi/BOSS-<version>.msi"

# Confirm the signature landed
powershell -Command "Get-AuthenticodeSignature 'composeApp/build/compose/binaries/main/msi/BOSS-<version>.msi'"
```

## Production Release Process

1. **Trigger Release**: Use GitHub Actions workflow_dispatch
2. **Automatic Process**:
   - Version increment
   - Cross-platform builds with code signing
   - macOS notarization
   - Windows MSI signing (x64 enforced, arm64 best effort)
   - GitHub release creation
   - Artifact upload

## Security Best Practices

1. **Certificate Protection**:
   - Store certificates securely
   - Use strong passwords
   - Rotate app-specific passwords regularly

2. **Access Control**:
   - Limit repository access
   - Use environment protection rules
   - Enable required status checks

3. **Monitoring**:
   - Monitor signing logs
   - Track certificate expiration
   - Set up alerts for failed builds

## Troubleshooting

### macOS Issues
- **Certificate not found**: Check keychain access and certificate name
- **Notarization fails**: Verify Apple ID and app-specific password
- **Team ID mismatch**: Ensure team ID matches certificate

### Windows Issues
- **Signing silently skipped**: `CODE_SIGNING_CERT_HOST` is empty or missing, so
  the `Check if signing secret is available` step set `can_sign=false`
- **KeyLocker connection fails**: Check API credentials and host
- **Certificate not accessible**: Verify KeyLocker setup and permissions
- **MSI signing fails**: read the `Sync KeyLocker Certificate to the Windows Store`
  step first - `healthcheck` covers the credential, `ksp list` the KSP registration,
  `keypair ls` the alias, and a failing `certsync` means signtool will not find a
  certificate. The `Check SMKSP Log` step prints the tail of
  `%USERPROFILE%\.signingmanager\logs\smksp.log`, though "log not found" is normal
  when the failure happened before the KSP was ever loaded
- **`signCommand ... FAILED` but the step is green**: expected, `smctl sign` exits 0
  regardless. `Verify MSI Signature` is what fails the job
- **ARM64 MSI unsigned**: Expected if the x64 client tools cannot run under
  emulation on the ARM runner; the run emits a warning and ships the MSI anyway

## Cost Considerations

### macOS
- **Apple Developer Program**: $99/year
- **Notarization**: Free with developer account

### Windows
- **DigiCert Code Signing**: ~$300-500/year
- **KeyLocker Service**: Additional monthly fee

## Support

For issues with this setup:
1. Check GitHub Actions logs for specific errors
2. Verify all secrets are correctly configured
3. Test certificate access locally first
4. Contact DigiCert support for KeyLocker issues
5. Contact Apple Developer Support for notarization issues