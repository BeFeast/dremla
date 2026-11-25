# Release signing

Release APKs are signed with one key, alias `dremla-release`. The keystore exists only in the secret store (Infisical). CI reads it at run time through a Universal Auth machine identity with read-only access, writes it to a private temporary directory for the duration of one job, and removes that directory at the end of the job.

Issue #7 sets this up and proves it with the signing smoke workflow. The release workflow (#8) reuses `scripts/signing-dir-create.sh`, `scripts/resolve-signing.sh`, `scripts/signing-cert-digest.sh` and `scripts/signing-dir-remove.sh`.

## Secret store layout

Environment `prod`, folder `/dremla/release/signing`:

| Key | Value |
| --- | --- |
| `RELEASE_KEYSTORE_B64` | base64 of the PKCS12 keystore; line breaks are allowed |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password, one line |
| `RELEASE_KEY_ALIAS` | `dremla-release` |
| `RELEASE_KEY_PASSWORD` | key password, one line, equal to `RELEASE_KEYSTORE_PASSWORD` |

A PKCS12 keystore made by `keytool` or `openssl` has a single password: `keytool` ignores a separate key password for PKCS12. `resolve-signing.sh` therefore fails when the two password values differ, instead of letting signing fail later.

The machine identity uses Universal Auth and can read secret values in that folder of `prod`, and nothing else. The scripts do not revoke the access token after use, so give the identity a short access token TTL (minutes).

## Repository configuration

Names only; the values are set by the operator in the repository settings.

- Actions secrets: `INFISICAL_CLIENT_ID`, `INFISICAL_CLIENT_SECRET`.
- Actions variables:
  - `INFISICAL_API_URL`: the API base, `https://` only, ending in `/api` (`https://<infisical-host>/api`).
  - `INFISICAL_PROJECT_ID`: the project that holds the folder above.
  - `RELEASE_SIGNING_CERT_SHA256`: 64 lowercase hex digits, the SHA-256 of the DER-encoded signing certificate. This is the value `apksigner verify --print-certs` prints as `certificate SHA-256 digest`; `keytool -list` shows the same value in upper case with colons as the certificate fingerprint (SHA-256). It is public.

To compute the variable when the keystore is created, from the keystore and a file that holds its password (both wherever the operator generates them). `sha256sum` prints the digest followed by ` -`; the `cut` keeps the digest only:

```bash
keytool -exportcert -alias dremla-release -keystore <keystore.p12> -storetype PKCS12 \
  -storepass:file <password-file> | sha256sum | cut -d' ' -f1
```

## Scripts

All five take no arguments. The scripts that run `keytool` take it from `$JAVA_HOME/bin` when `JAVA_HOME` is set, otherwise from `PATH`, and run it with `-J-Duser.language=en`, so its messages are in English under any locale. This changes only the message language, not the character encoding `keytool` reads the password files with (on JDK 17, `LC_ALL=C` would change both).

- `scripts/signing-dir-create.sh` makes the signing directory: `mktemp -d "$RUNNER_TEMP/sign.XXXXXX"`, then `chmod u=rwx,go=,u-s,g-s,o-t`: mode 0700 with the setuid, setgid and sticky bits cleared, checked with the same rule as the resolver uses. The special bits are cleared explicitly because a directory made under a setgid parent inherits the setgid bit, and `chmod 700` or `chmod u=rwx,go=` keep that bit on some systems. When `GITHUB_ENV` is set it appends `DREMLA_SIGNING_DIR=<path>` to that file, so the later steps of the job get the variable, and prints `signing directory: created` without the path; otherwise it prints the `DREMLA_SIGNING_DIR=<path>` line on stdout. It fails, and leaves no directory behind, when `RUNNER_TEMP` is not set, not absolute, not a directory or contains a control character, when `DREMLA_SIGNING_DIR` is already set (a second directory would hide the first from the cleanup), or when `GITHUB_ENV` cannot be written.
- `scripts/resolve-signing.sh` needs `INFISICAL_API_URL`, `INFISICAL_PROJECT_ID`, `INFISICAL_CLIENT_ID`, `INFISICAL_CLIENT_SECRET` and `DREMLA_SIGNING_DIR`, an existing empty directory owned by the caller with mode 0700: the owner has read, write and search permission, group and other have none. The setuid, setgid and sticky bits are not permissions and are ignored, so a setgid bit inherited from the parent does not fail the check. It logs in (`POST <api>/v1/auth/universal-auth/login`), reads the four keys (`GET <api>/v3/secrets/raw` for environment `prod` and path `/dremla/release/signing`, with `expandSecretReferences=false`, `include_imports=false` and `recursive=false` so that server defaults cannot change the values) and writes, mode 0600, into `DREMLA_SIGNING_DIR`:
  - `release.p12`: the decoded keystore;
  - `storepass`, `keypass`: the passwords, without a trailing newline, for `keytool -storepass:file` and `apksigner --ks-pass file:`;
  - `alias`: `dremla-release`.

  It fails, with a message that names the problem but no value, on: a missing or empty variable, a non-https URL, a directory that is not empty, not 0700, not owned by the caller or a symbolic link, a login or secrets request that does not return HTTP 200 (for example `Infisical login failed: HTTP 401`), a missing, empty, duplicated or hidden key, a password with a line break, an alias other than `dremla-release`, different store and key passwords, a value that is not base64, a keystore that `keytool` cannot open with that password and alias or whose alias is not a private key entry, and a private key that does not decrypt (checked by signing a certificate request with it, which is then discarded). On any failure it leaves `DREMLA_SIGNING_DIR` empty.
- `scripts/signing-cert-digest.sh` reads `DREMLA_SIGNING_DIR` and prints one line on stdout: the SHA-256 of the certificate, 64 lowercase hex digits.
- `scripts/signing-dir-remove.sh` removes the signing directory and is meant for an `if: always()` step. It reads `DREMLA_SIGNING_DIR` and `RUNNER_TEMP`. When `DREMLA_SIGNING_DIR` is unset or empty it prints `signing directory: never created` and exits 0; when the path does not exist it prints `signing directory: already gone` and exits 0. It refuses, removing nothing and exiting 1, a path that is not exactly `$RUNNER_TEMP/sign.<mktemp suffix>`, a symbolic link, something that is not a directory, and a directory not owned by the current user. Otherwise it gives every directory inside owner permissions back (so `rm` can empty it), overwrites every regular file, hidden ones included, with `shred --force --remove --iterations=1` when `shred` is on `PATH` (a `shred` failure is reported and removal goes on), removes the directory with `rm -rf`, checks that the path is gone and prints `signing directory: removed`. If the path is still there it fails. It does not follow symbolic links and prints no path.
- `scripts/test-signing.sh` is the self-test, run by `scripts/verify-outcome.sh` in CI. It uses a throwaway keystore and a stub `curl`; it makes no network call and uses no real secret. `jq` and `keytool` run through logging wrappers, so the test checks the argv of every `curl`, `jq` and `keytool` call, and the environment of every `curl` and `keytool` call, for secret values. It also runs the two directory scripts against scratch `RUNNER_TEMP` directories: create makes a 0700 directory, records it in `GITHUB_ENV`, clears a setgid bit inherited from a setgid `RUNNER_TEMP` and fails closed; the resolver accepts a directory with an inherited setgid bit and refuses one with group access; remove deletes a filled directory (hidden, read-only and permissionless entries, links that point outside), sends every file through `shred`, still removes the directory when `shred` fails or is missing, refuses paths outside `$RUNNER_TEMP/sign.*`, symbolic links and files, exits 0 when there is nothing to remove, and fails when the directory survives `rm`.

## Signing smoke workflow

`.forgejo/workflows/signing-smoke.yml` runs only on `workflow_dispatch` and only on `main` (a dispatch on another branch skips the job), on the `heavy` runner, with a 15 minute job timeout, read-only repository permissions and one run at a time (concurrency group `signing-smoke`). Its steps, each with its own timeout:

1. Checkout, without persisting the job token (2 minutes).
2. Check that the three variables and the two secrets are set (the secrets by presence only), that `INFISICAL_API_URL` starts with `https://`, that `RELEASE_SIGNING_CERT_SHA256` is 64 lowercase hex digits, and that `curl`, `jq`, `base64`, `sha256sum` and `$JAVA_HOME/bin/keytool` exist (1 minute).
3. Run `scripts/signing-dir-create.sh` (1 minute), which sets `DREMLA_SIGNING_DIR` for the later steps. `RUNNER_TEMP` comes from the runner environment, or from the `runner.temp` context when the variable is not set; step 6 uses the same value.
4. Run `scripts/resolve-signing.sh` (5 minutes). The secrets and variables reach it only through this step's environment.
5. Run `scripts/signing-cert-digest.sh` (2 minutes) and print:

   ```text
   release signing certificate SHA-256: <digest>
   matches RELEASE_SIGNING_CERT_SHA256: yes
   ```

   On a mismatch the step fails and prints both digests (they are public).
6. `if: always()`: run `scripts/signing-dir-remove.sh` (1 minute), which prints `signing directory: removed` (or `never created` when step 3 did not run or failed before setting the variable).

Timeout invariant: the sum of the step timeouts plus a margin for job setup stays below the job timeout. Today that is 2 + 1 + 1 + 5 + 2 + 1 = 12 minutes against 15. A hung step is then ended by its own timeout while the job is still running, so the cleanup step always gets its turn; a hang that ran into the job timeout instead could stop the job before the cleanup step. Check the sum whenever a step or a timeout changes; the workflow header states the same rule.

The workflow uploads no artifact.

## Guarantees

- No secret value in git. The repository holds key, secret and variable names only; the self-test generates its throwaway keystore and credentials at run time. `.gitignore` excludes keystore files (`*.keystore`, `*.jks`, `*.p12`, `*.pfx`).
- No secret value in logs. The scripts never print a value and turn xtrace off; `jq` error messages, which can quote their input, are discarded; of `keytool` output only its `keytool error:` line is shown. The runner masks the two repository secrets. The workflow does not use `::add-mask::` for the resolved passwords: they are never printed, and masking them would depend on how the runner treats the command line itself.
- No secret value in argv. The login body goes to `curl` on stdin, the bearer token through a header file in `DREMLA_SIGNING_DIR`, passwords through files (`-storepass:file`, `-keypass:file`). Only the `jq` process that builds the login body has the client credentials in its environment; `curl` and `keytool` do not. The self-test checks all of this on every run.
- No artifacts, and no keystore copy after the job: the resolver's intermediate files (login response, header file, secrets response) are deleted as soon as they are used, and the cleanup step removes `DREMLA_SIGNING_DIR` whatever the outcome of the earlier steps, as long as the job is still alive (see the timeout invariant).

Limits:

- The `heavy` runner runs jobs in host mode, all as one OS user, and the same label runs pull request CI. On such a runner the 0700 and 0600 modes do not separate jobs: a job running at the same time, or a process left behind by an earlier job, can read the signing directory and the environment of the resolver, which holds the client secret. Run the smoke only on a runner that takes one job at a time (capacity 1) and does not run jobs from fork pull requests, or give it a dedicated signing label; choosing between the two is the operator's decision.
- `shred` cannot guarantee that data is overwritten on copy-on-write or flash storage, which is why the directory lives in the runner temp directory for minutes only.
- The cleanup step does not run if the run is cancelled, if it hits the job timeout, or if the runner process dies. Do not cancel a running smoke; let the step timeouts end a hung step. After a cancelled or killed run, remove any leftover `sign.*` directory from the runner temp directory by hand.
- The scripts do not revoke the access token after use (see the access token TTL above).

## Recording the result on issue #7

1. In the repository's Actions tab, choose the `signing-smoke` workflow and run it on `main`.
2. In the job log, check the two lines above and `signing directory: removed`.
3. Comment on issue #7, with nothing else from the log:

   ```text
   Signing smoke run: <link to the run>
   release signing certificate SHA-256: <digest>
   matches RELEASE_SIGNING_CERT_SHA256: yes
   signing directory: removed
   ```
