# Unofficial Telegram CLI

A Java 25 terminal-client project targeting **Windows and Linux**, with TDLib
planned as its Telegram engine. Independent of and not endorsed by Telegram.

## Status

**Offline scaffold only—not yet a Telegram client.** Help/version, local
diagnostics, and a synthetic demo are available. There is no real login, TDLib
loading, messaging, session storage, or full-screen TUI yet.

## Build and try

Use a full JDK 25. The Maven Wrapper downloads pinned Maven 3.9.16 and verifies
its distribution checksum. No Telegram credentials or native libraries are needed.
With [mise](https://mise.jdx.dev/), run `mise trust`, `mise install`, and prefix
commands with `mise exec --` if the toolchain is not activated in your shell.

```sh
./mvnw --batch-mode --no-transfer-progress verify
java -jar target/telegram-cli.jar --help
java -jar target/telegram-cli.jar diagnostics --json
java -jar target/telegram-cli.jar demo --json
```

On Windows PowerShell, use `.\mvnw.cmd` instead of `./mvnw`. The development JAR
requires Java 25; it does not bundle a runtime. Native Windows execution and the
future terminal backend require separate validation.

## Documentation

Detailed documentation lives in the [GitLab wiki](https://gitlab.com/wqyeo/telegram-cli/-/wikis/home):

- [Development](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Development)
- [Architecture](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Architecture)
- [Security and roadmap](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Security-and-Roadmap)
- [Scaffolding plan](https://gitlab.com/wqyeo/telegram-cli/-/wikis/Scaffolding-Plan)

## License

[MIT](LICENSE), copyright (c) 2026 Yeo Wen Qin. Third-party components retain their
own licenses; future releases must include their required notices.
