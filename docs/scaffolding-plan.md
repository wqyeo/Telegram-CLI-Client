# Java 25 application scaffolding plan

## 1. Scope and decision status

This is a plan, not an implemented application. The current change creates only
`README.md`, `LICENSE`, `.gitignore`, and this document. It does not create Java
sources, a build, CI configuration, or native binaries.

Confirmed requirements:

- Java 25, targeting native Windows and Linux execution.
- A terminal Telegram client using a personal account, not the Bot API.
- Original project material licensed under MIT, attributed to Yeo Wen Qin.
- Commit author and committer: `Yeo Wen Qin <yeo.wen.qin@pm.me>`.
- SSH-signed commits; no private key material stored in the repository.
- Remote: `git@gitlab.com:wqyeo/telegram-cli.git`.
- No push is part of this planning change.

Proposed implementation defaults:

- A single Maven module; split modules only when a demonstrated boundary needs it.
- Official TDLib Java/JNI bindings, not a third-party TDLib fork initially.
- Picocli, Jackson, JUnit 5, and a provisional Lanterna terminal backend.
- Windows x64 and Linux x64 as the first release targets; ARM64 is deferred.
- Windows Terminal as the primary Windows terminal; common UTF-8 Linux terminals,
  SSH, and tmux as Linux test environments. Exact minimum OS/glibc versions must
  be documented after the native-library spike. WSL does not replace Windows tests.

Library versions and the terminal backend remain subject to compatibility checks.
No Kotlin, Java preview features, Spring Boot, remote service, custom MTProto
implementation, or duplicate persistent message database is planned.

## 2. Proposed repository layout

The following paths are future scaffolding, not files already present:

```text
telegram-cli/
  pom.xml
  mvnw
  mvnw.cmd
  .mvn/wrapper/                  # Pinned wrapper distribution and checksums
  .gitlab-ci.yml
  README.md
  LICENSE
  .gitignore
  docs/
    scaffolding-plan.md
    development.md
    security.md
    native-dependencies.md
  src/main/java/com/gitlab/wqyeo/telegramcli/
    Main.java                    # Composition root and process exit handling
    cli/                         # Picocli commands and output contracts
    application/                 # Use cases and ordered state processing
    model/                       # Application-owned records and sealed events
    telegram/                    # Narrow engine interface
    telegram/tdlib/              # JNI integration and generated-type mapping
    tui/                         # Screen state, rendering, navigation, input
    platform/                    # Paths, permissions, openers, credential access
    config/                      # Configuration loading and validation
  src/main/resources/
    application-defaults.json    # Non-secret defaults only
  src/test/java/com/gitlab/wqyeo/telegramcli/
    cli/
    application/
    telegram/
    tui/
    platform/
    config/
  src/test/resources/fixtures/   # Synthetic, non-identifying data only
  packaging/
    windows/
    linux/
```

Proposed Maven coordinates: `com.gitlab.wqyeo:telegram-cli`. The executable name
`tgc` is provisional. The display title must identify the app as unofficial;
repository coordinates are not a final product-brand decision.

## 3. Build and dependency boundaries

- Set `maven.compiler.release` to `25` and require a JDK 25 toolchain.
- Pin Maven, plugin versions, and dependency versions; do not use dynamic versions.
- Commit Maven Wrapper launchers for Bash-compatible systems and Windows.
- Verify wrapper/distribution integrity and provide both shell usage examples.
- Keep the default build independent of native TDLib and Telegram credentials.
- Introduce a separate native/integration profile for TDLib checks. It must fail
  clearly when prerequisites are missing, not silently skip a requested check.
- Package the exact generated Java API and JNI library built from the same pinned
  TDLib revision. Document their origin, checksums, dependencies, and licenses.
- Do not download or execute unverified native artifacts at application startup.

Generated TDLib Java sources and JNI artifacts need an explicit build strategy:
prefer a reproducible native pipeline that publishes a paired internal artifact,
then consume that artifact only in the native-enabled build. Decide the exact
artifact layout during the spike before wiring the adapter into the application.
The default fake-engine skeleton must remain buildable without that artifact.

The initial skeleton should expose only help/version and explicit diagnostic or
mock functionality. Unimplemented login, TUI, and messaging commands must report
that they are unavailable; they must never simulate a successful real operation.

## 4. Application architecture

```text
TUI actions / CLI commands
          |
Application services -> ordered state reducer -> immutable view snapshots
          |
Telegram engine interface
          |
TDLib adapter -> official Java/JNI bindings -> TDLib -> Telegram
```

### Ownership and concurrency

- One process owns each profile's session/database directory, protected by an
  exclusive cross-platform file lock. A second process fails with a useful error.
- Initially, the TUI and standalone commands are alternative modes. CLI commands
  cannot simultaneously access a profile held by the TUI.
- A local daemon is deferred. If added, transport must work on Windows and Linux;
  do not assume Unix sockets are the only IPC mechanism.
- TDLib callbacks enqueue events to one ordered state-processing path. Update
  state before publishing results that depend on it. Do not dispatch each update
  to unrelated concurrent tasks or drop state events to reduce render load.
- Keep rendering and blocking auxiliary work outside the state executor. Virtual
  threads may help blocking tasks but do not replace ordered event handling.
- Apply backpressure or fail visibly on overload; throttle view refreshes rather
  than discard message, deletion, authentication, or file-state updates.
- Use records and sealed interfaces for application-owned data and events. Do
  not spread generated TDLib types through the CLI or renderer.

### State and lifecycle

TDLib owns Telegram persistence and synchronization. The application maintains
bounded in-memory indexes, command state, and view snapshots. Store configuration
and minimal UI preferences separately; do not add a message-history database.

Model authorization states explicitly. Support QR/code/email/password flows as
provided by the pinned TDLib version; never promise SMS delivery. Use an existing
account for the first milestone and explain unsupported states without retry loops.

Track pending, sent, and failed messages. Reconcile temporary message identifiers
with successful-send updates. A request timeout is not proof of send failure;
do not blindly resend and create duplicates. Respect rate limits and restrictions.

Close TDLib and wait for its closed state before normal termination. Restore
terminal modes and release locks in cleanup paths. Closing the app must not log
out the account; logout is a separate explicit action.

## 5. Terminal and command contracts

### Interactive TUI

Start with a chat list, conversation pane, composer, and connection status.
Validate Lanterna in the actual Windows console using any required native
terminal bridge dependencies. Disable graphical terminal fallback for production
terminal mode. Do not assume its pure-Java description guarantees every Windows
console path is dependency-free.

Test multiline input, paste, key modifiers, resize, emoji/wide-character wrapping,
non-Latin text, and clean recovery after exceptions. Keep arrow keys and visible
help as defaults; Vim bindings and graphics protocols are optional enhancements.
Reject noninteractive TUI invocation with a clear diagnostic rather than hanging.

### CLI

Provide help/version first. Later commands may cover login, chat listing,
history, sending through stdin/files, and explicit attachment download.

- Machine-readable JSON on stdout; diagnostics on stderr.
- Version the JSON contract before consumers depend on it. Preserve identifier
  precision and do not assume message IDs are globally unique across chats.
- Proposed exit codes: `0` success, `1` operation failure, `2` invalid usage,
  `3` authentication required, `4` session busy, `5` outcome unknown/timeout.
- Resolve recipients explicitly and reject ambiguous names.
- Distinguish queued/accepted from successfully sent. Default send behavior should
  await a terminal result within a documented timeout; report unknown outcomes.
- Avoid secret/message input through command arguments or persisted command history.
- Do not implement stealth viewing or suppress required read/typing/presence behavior.

## 6. Platform, security, and licensing

Keep platform details behind small interfaces:

- Windows application directories versus Linux XDG directories.
- User-restricted Windows ACLs versus restrictive Linux permissions.
- Credential access with a secure interactive fallback if no keyring is available.
- Optional platform attachment openers and editor integration, invoked with
  argument arrays, never untrusted shell-string interpolation.

Protect session material as account access. Require a non-empty database key in
real-session mode; do not write that key beside the database. Treat attachment
files, editor temporary files, exports, terminal scrollback, and crash dumps as
separate exposure surfaces. Database encryption is not blanket file protection.
Sanitize terminal escape sequences and control characters in untrusted content.
Redact diagnostics and never log codes, passwords, keys, or message bodies by default.

Register the application's own API ID/hash. Do not commit operational credentials,
copy another client's credentials, or ask for them during documentation/skeleton
work. Future credential provisioning must be documented before real login tests.

Telegram cloud chats are not E2EE. Defer Secret Chats until device-local key and
self-destruct behavior is implemented. Respect edits, deletions, expiration,
protected content, read receipts, typing, and presence. Do not preserve expired
content in logs or secondary caches. Channel browsing is blocked until sponsored
message obligations are implemented. Recheck Telegram's terms before distribution,
including naming/logo rules and restrictions involving AI/ML uses of platform data.

The project's original material is MIT, copyright 2026 Yeo Wen Qin. Dependencies
are not relicensed by that choice. Audit TDLib, terminal dependencies, runtime,
cryptography dependencies, and native redistributables; ship required notices
and retain a dependency/license inventory with releases.

## 7. Implementation stages and acceptance gates

### Stage 1: Offline skeleton

Create Maven/Wrapper, Java 25 entry point, Picocli help/version, basic configuration,
application interfaces, fake engine, and JUnit tests. Add GitLab CI for the default
build; it needs no Telegram session, credentials, or TDLib installation.

Acceptance:

- A clean JDK 25 environment runs `./mvnw verify` on Linux and
  `.\mvnw.cmd verify` on Windows.
- Help/version, invalid arguments, stdout/stderr separation, and exit behavior
  have automated tests. There are no misleading successful stub operations.
- The fake engine exercises ordered state changes without networking.

### Stage 2: Cross-platform native and terminal spike

Build the paired TDLib JNI/API artifact, load it on native Windows and Linux,
render a basic full-screen mock interface, and create self-contained app images.
Investigate launcher native-access permissions required by the chosen JDK/native
bridges and document narrowly appropriate settings.

Acceptance:

- Both app images run on clean target systems without a preinstalled JDK.
- Required dependent libraries resolve; missing/incorrect architecture fails clearly.
- Input, paste, resize, Unicode, and terminal restoration pass on both systems.
- Record tested terminal, OS, CPU, and Linux libc versions. Failures may trigger
  a terminal-backend change before full UI development, not an automatic language change.

### Stage 3: Real Telegram vertical slice

Using user-provided credentials securely, authenticate a controlled existing
account, list chats, and send/receive in Saved Messages. Exit and restore the
session without repeated login. Verify the exclusive profile lock and connection
status through a disconnect/reconnect cycle.

Acceptance: no false send success, no blind duplicate sends, no credentials in
logs, expected session permissions, and clean shutdown on both platforms.

### Stage 4: Daily-use messaging

Add paginated history, switching, replies, edits/deletions, unread/read state,
typing/presence, attachment progress, in-chat search, and bounded caches. Use
explicit placeholders for unsupported content rather than silently hiding it.

Test synthetic event sequences, send lifecycle reconciliation, expiration,
permissions, terminal-control injection, Unicode, and rendering snapshots. Keep
live tests opt-in, use controlled accounts, and avoid unsolicited messaging.

### Stage 5: Release hardening

Create signed/checksummed distribution artifacts and complete third-party notices,
operator documentation, and platform support matrix. GitLab Linux runners can
perform default/offline checks; native Windows tests require an actual Windows
runner, whose availability must be arranged before claiming full CI coverage.
Interactive terminal tests need a real/pseudo console, not just a headless job.

Build Windows packages on Windows and Linux packages on Linux. A Windows ZIP can
contain a console launcher, bundled runtime, JNI library, and dependencies; a
Linux archive must declare its libc baseline. Installers and distro packages can
follow. The native release lane is distinct from the fast offline build.

## 8. Deferred features

Multiple accounts, a concurrent session daemon, plugins, rich inline images,
voice/video calls, Stories, payments, advanced administration, and ARM64 support
are deferred. Secret Chats and channels have the additional gates described above.
A private prototype does not itself demonstrate public-release API compliance.

## 9. Repository and commit hygiene

Use repository-local Git identity/signing configuration, not changes to the
operator's global identity. Sign commits with SSH and verify the result against
the selected public key. An unavailable signing key blocks a signed commit;
never silently substitute an unsigned commit or another identity.

Do not commit local agent state, runtime data, downloaded binaries, private keys,
credentials, or real-message fixtures. Ignore rules are safeguards, not substitutes
for reviewing the staged diff. A push requires a separate explicit request.

## 10. References

- [Telegram APIs and TDLib](https://core.telegram.org/api#tdlib-build-your-own-telegram)
- [TDLib concepts and ordered updates](https://core.telegram.org/tdlib/getting-started)
- [Official TDLib Java/JNI example](https://github.com/tdlib/td/tree/master/example/java)
- [Application registration](https://core.telegram.org/api/obtaining_api_id)
- [Telegram authorization](https://core.telegram.org/api/auth)
- [Telegram API terms](https://core.telegram.org/api/terms)
- [Lanterna](https://github.com/mabe02/lanterna)
- [Picocli](https://picocli.info/)
- [Java 25 jpackage](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html)
