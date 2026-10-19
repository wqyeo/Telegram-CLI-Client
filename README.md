# Unofficial Telegram CLI

A Java 25 terminal-client project targeting **Windows and Linux**, with TDLib
planned as its Telegram engine. Independent of and not endorsed by Telegram.

## Status

**Offline prototype—not yet a usable Telegram account client.** Includes synthetic
Linux terminal UIs, explicit local profile/encrypted-vault tools and an account-free
native-worker check. The read-only preview uses public fixtures, not a real account.
There is no operational login, TDLib account database, live messaging or account-session
reuse. Windows recipes are **unverified**; native Windows terminal execution is refused.

The [G1 native-hardening source checkpoint](docs/native-g1-checkpoint.md) preserves
experimental patches, PUBLIC fixtures and Java lease work. Its guarded factory remains
fenced; selected contracts and component checks are **not** engine-wide acceptance.

The next product milestone is an ordinary-conversation read-only session through authenticated
reopen. The [activation-blocker register](docs/read-only-activation-register.md) and
[shared-reader contract proposal](docs/shared-reader-contract-proposal.md) define the remaining
work; they do not enable a real account client or approve native/account execution.

## Build and try

Use a full JDK 25. The Maven Wrapper downloads pinned Maven 3.9.16 and verifies
its distribution checksum. No Telegram credentials or native libraries are needed.
With [mise](https://mise.jdx.dev/), run `mise trust`, `mise install`, and prefix
commands with `mise exec --` if the toolchain is not activated in your shell.

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
java -jar target/telegram-cli.jar --help
java -jar target/telegram-cli.jar diagnostics --json
java -jar target/telegram-cli.jar demo --json
java -jar target/telegram-cli.jar tui --demo
java -jar target/telegram-cli.jar tui --read-only-demo
```

The read-only guide advances fixed public inputs with Enter: **never type credentials
into it**. It then shows bounded synthetic chats and Saved Messages text, without a
composer or send action. The original demo remains separate.

Keep `target/lib/` beside the development JAR: Lanterna and the original Bouncy Castle
JAR are separate runtime dependencies; Lanterna remains replaceable. Java 25 is required;
self-contained Linux images are built separately. Checks are manual; CI/CD is deferred.

For the optional JNI build and app image, see the wiki's
[offline prototype guide](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Offline-Prototype).
On Windows PowerShell use `.\mvnw.cmd`; Windows execution is not validated by Linux tests.

## Documentation

Detailed documentation lives in the [GitLab wiki](https://gitlab.com/wqyeo/telegram-cli/-/wikis/home):

- [Offline read-only preview and local setup](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Offline-Read-Only-Preview)
- [Development](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Development)
- [Architecture](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Architecture)
- [Security and roadmap](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Security-and-Roadmap)
- [Scaffolding plan](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Scaffolding-Plan)

## License

[MIT](LICENSE), copyright (c) 2026 Yeo Wen Qin. Third-party components retain their
own licenses; see [third-party notices](THIRD-PARTY-NOTICES.txt).
