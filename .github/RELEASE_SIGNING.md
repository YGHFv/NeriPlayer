# Release signing

Signed releases are built by GitHub Actions without committing the keystore or
passwords to the repository.

## Automatic releases

A push to `master` in `YGHFv/NeriPlayer` runs the complete Android CI gate. After
all required checks pass, the workflow:

1. Restores the release keystore from repository secrets.
2. Builds signed APKs for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
3. Verifies build metadata, checksums, and every APK signature.
4. Creates a GitHub Release tagged as `NeriPlayer-<version>`.

Pull requests and forks never receive the release secrets and only produce
unsigned verification artifacts. A commit message beginning with `[skip ci]`
does not publish a release.

## Required repository secrets

Configure these under **Settings > Secrets and variables > Actions > Repository
secrets**:

| Secret | Source in the local signing directory |
| --- | --- |
| `KEYSTORE_B64` | Base64 contents of `reamicro-release.jks`, normally `reamicro-release-base64.txt` |
| `KEYSTORE_PASSWORD` | `REAMICRO_RELEASE_STORE_PASSWORD` |
| `KEY_ALIAS` | `REAMICRO_RELEASE_KEY_ALIAS` |
| `KEY_PASSWORD` | `REAMICRO_RELEASE_KEY_PASSWORD` |

The workflow also accepts a base64 value containing line breaks and strips them
before decoding.

## Configure with GitHub CLI

Authenticate once, then run the helper from the repository root:

```powershell
gh auth login
pwsh -File .github/scripts/configure_release_signing.ps1 `
  -SigningDirectory E:\MTool\Work\reamicro\signing
```

The helper reads the local files, uploads only the four repository secrets, and
does not print their values.

## Security rules

- Do not commit the `.jks` file, its base64 copy, or the plaintext secrets file.
- Keep an offline backup of the keystore and passwords.
- Never change the signing key unless an upgrade break is intentional: Android
  rejects in-place upgrades signed by a different certificate.
- If the signing key is rotated, update all four secrets together.
