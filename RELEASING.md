# Releasing

kt-mcpx is published to Maven Central through the [Central Portal](https://central.sonatype.com), as
four artifacts in the group `app.oreshkov.ktmcpx`: `kt-mcpx-core`, `kt-mcpx-ktor`, `kt-mcpx-stdio` and
`kt-mcpx-testing`. Pushing a version tag runs [`release.yml`](.github/workflows/release.yml), which
tests, signs, attests, uploads and drafts the GitHub release; nothing in the repository holds keys or
credentials.

## One-time setup

1. **Namespace**: `app.oreshkov` must be verified in the Central Portal. Until it is, uploads fail
   with an authorization error.
2. **Signing key**: a PGP key that signs with its primary key (Maven Central checks signatures against
   primary keys), with an expiry date, its public half on `keyserver.ubuntu.com` and
   `keys.openpgp.org`.
3. **Portal token**: a user token from the Central Portal account page, as the Base64 of
   `username:password`. Tokens expire, so replace the secret before then.
4. **GitHub environment** `release` (Settings → Environments), with yourself as required reviewer and
   `v*` as the only deployment tags, holding these secrets:

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | the ASCII-armored private key |
   | `SIGNING_KEY_PASSWORD` | its passphrase |
   | `CENTRAL_TOKEN` | the Portal token |

5. **Immutable releases** on (Settings → General → Releases), so a published release's tag and assets
   can't change.

## Cutting a release

1. `gradle.properties`: set `version` to the release version (no `-SNAPSHOT`), and the installation
   snippet in `README.md` and its compatibility table to the same version.
2. `CHANGELOG.md`: move the `Unreleased` entries under a new `## [X.Y.Z] - YYYY-MM-DD` heading dated the
   day you tag, leaving `## [Unreleased]` empty above it, and update the link references at the bottom.
   That section becomes the release notes.
3. Check locally: `./gradlew build`, both conformance revisions against the fixture server (see the
   README), and the runtime classpath of each published module
   (`./gradlew :kt-mcpx-core:dependencies --configuration runtimeClasspath`, and the same for the
   others): nothing beyond the SDK, Ktor, kotlinx and the other kt-mcpx modules.
4. Merge these changes to `main`, then tag the merged commit and push only the tag:

   ```shell
   git switch main && git pull --ff-only
   git tag -s vX.Y.Z -m "kt-mcpx X.Y.Z"
   git push origin vX.Y.Z
   ```

5. `release.yml` runs `build` on Linux and Windows, then the `release` job waits for your approval.
   Once approved, it checks that the tag matches `version`, builds the signed bundle with `stage`,
   checks that every file is signed and that only the four modules are in it, attests the jars, POMs
   and module files, uploads the bundle to the Portal and waits until the Portal has validated it. The
   run's summary names the deployment. Only then does it draft the GitHub release with the notes from
   the changelog and the jars attached. A version with a hyphen is drafted as a pre-release.
6. In the Portal, check the validated deployment and **publish** it. Wait until it shows *Published*.
7. On GitHub, review the draft release and **publish** it.
8. Set `version` to the next `-SNAPSHOT` in `gradle.properties`.

A released jar can be checked against its provenance:

```shell
gh attestation verify kt-mcpx-core-X.Y.Z.jar --repo aoreshkov/kt-mcpx
```

## By hand

If the workflow can't run, the same bundle can be built and uploaded locally. Put the signing key in
`~/.gradle/gradle.properties`, never in the repository:

```properties
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...
signingInMemoryKeyPassword=...
```

```shell
./gradlew stage
cd build/staging-repository && zip -r ../kt-mcpx-bundle.zip . && cd -
curl --fail --request POST \
  --header "Authorization: Bearer $CENTRAL_TOKEN" \
  --form bundle=@build/kt-mcpx-bundle.zip \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED"
```

`stage` empties the staging directory first, and signs only when the key is configured; unsigned
files are rejected by the Portal. A release made this way has no provenance attestation.
