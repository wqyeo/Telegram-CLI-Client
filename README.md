# Unofficial Telegram CLI

A planned terminal-first Telegram client for **Windows and Linux**, written in
**Java 25** and powered by **TDLib**.

This is an independent project using the Telegram API. It is not affiliated with
or endorsed by Telegram.

## Status

**Planning only.** This repository currently contains the scaffolding plan,
licensing, and repository hygiene rules. There is no application code, Maven
build, native TDLib integration, CI pipeline, or installable release yet.

See [the scaffolding plan](docs/scaffolding-plan.md) for the proposed project
layout, architecture, implementation stages, and acceptance criteria.

## Direction

- Native Windows and Linux support, tested from the first runnable prototype.
- A keyboard-driven, full-screen terminal UI for everyday messaging.
- A small scriptable CLI sharing the same application core.
- Official TDLib Java/JNI bindings for Telegram connectivity and local state.
- Platform-specific packages with a bundled Java runtime and native libraries.
- No server framework, separate message-history database, or required GUI.

## Proposed stack

| Concern | Planned choice |
| --- | --- |
| Language and runtime | Java 25, without preview features |
| Build | Maven with a pinned Maven Wrapper |
| Telegram integration | Official TDLib Java/JNI bindings behind an adapter |
| CLI | Picocli |
| Full-screen TUI | Lanterna, subject to native Windows/Linux validation |
| Configuration and JSON | Jackson |
| Tests | JUnit 5 |
| Packaging | `jlink` / `jpackage`, per target platform |

Dependency versions will be pinned and compatibility-tested when implementation
starts. Native TDLib builds and terminal behavior are validation gates, not
capabilities already demonstrated by this repository.

## Initial scope

The first vertical slice will launch on both target operating systems,
authenticate an existing account, list chats, send and receive in Saved Messages,
and restore the session after a clean restart. Broader messaging features follow
only after this foundation works.

## Security and platform constraints

Telegram cloud chats are not end-to-end encrypted; Secret Chats have distinct,
device-local semantics and are outside the initial scope. Login must follow the
actual TDLib authorization states rather than assume SMS delivery.

Session material, API credentials, private keys, downloaded messages, and media
must not be committed. Use synthetic data for tests and redact diagnostics.
Channel access requires an implementation of the applicable sponsored-message
requirements before it is enabled.

See the plan for the security boundaries and links to Telegram's terms.

## License

This project's original material is licensed under the [MIT License](LICENSE).
Copyright (c) 2026 Yeo Wen Qin.

Third-party libraries, native binaries, and bundled runtimes retain their own
licenses. Their notices and redistribution obligations must be included in any
future release.
