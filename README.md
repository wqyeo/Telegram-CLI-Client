# Unofficial Telegram CLI

A Java 25 terminal-client project targeting **Windows and Linux**, with TDLib
planned as its Telegram engine. Independent of and not endorsed by Telegram.

## Status

**Offline prototype—not yet a usable Telegram client.** Includes local diagnostics,
a synthetic Linux terminal UI, and an opt-in, account-free TDLib JNI probe.
There is no real login, messaging, or session storage. Windows recipes are
**unverified**; its native terminal backend remains unavailable.

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
```

Keep `target/lib/` beside the development JAR: Lanterna is a separate, replaceable
library. The JAR requires Java 25; self-contained Linux images are built separately.
Checks are invoked manually; CI/CD work is deferred.

For the optional JNI build and app image, see the wiki's
[offline prototype guide](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Offline-Prototype).
On Windows PowerShell use `.\mvnw.cmd`; Windows execution is not validated by Linux tests.

## Documentation

Detailed documentation lives in the [GitLab wiki](https://gitlab.com/wqyeo/telegram-cli/-/wikis/home):

- [Development](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Development)
- [Architecture](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Architecture)
- [Security and roadmap](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Security-and-Roadmap)
- [Scaffolding plan](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Scaffolding-Plan)

## License

[MIT](LICENSE), copyright (c) 2026 Yeo Wen Qin. Third-party components retain their
own licenses; see [third-party notices](THIRD-PARTY-NOTICES.txt).
