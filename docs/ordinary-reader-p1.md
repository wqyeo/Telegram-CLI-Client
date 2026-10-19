# P1 ordinary-conversation reader — PUBLIC implementation

P1 adds working application-side reader behavior to the existing typed bridge and ordered
coordinator. It is **not a live Telegram client, native admission or authenticated reopen**.
There is no new CLI/TUI command or genuine launcher. The original Saved Messages preview
and original demo retain their v1 entry points.

## Shared PUBLIC entry points

- `SyntheticReadOnlySession.ordinaryReader()` uses in-memory private-codec round trips.
- `ProcessReadOnlySession.ordinaryReader(process, generation, budgets)` attaches an already
  launched **PUBLIC fixture process**, through `attachPublicReaderFixture`. It neither
  launches a genuine worker nor accepts credentials/providers/profiles.
- Both implement the existing `ReadOnlyPreviewSession` facade: explicit PUBLIC auth
  advancement, directory paging, `searchLoaded`, `selectChat`, `olderPage`, `latestPage`,
  `cancelRead`, refresh and logical disposal/fixture Close.

After PUBLIC auth, only the directory loads automatically. There is no Saved chat identity
or automatic conversation selection. The fixture contains two one-to-one conversations
(1001/1002) with overlapping message IDs, plus an explicitly unsupported group. Selection
supports `ChatKind.USER` only; unknown/unsupported selection preserves the current view and
publishes NOT_AVAILABLE/UNSUPPORTED. No server search, send, media fetch or read-marking
operation exists. Fixture edit/remove/incoming controls are server-side test inputs, never
viewer mutation commands.

`Snapshot.reader()` supplies account identity, chat/selection/window tokens, visible rows,
continuations, bounded incoming indication and retained count. Search coverage is LOADED_ONLY;
no match says nothing about conversations outside the retained directory. Search remains local.
The requested/accepted history IDs distinguish successful response acceptance from mere
absence of a pending request. `olderPage()` returns false only for explicit END; UNKNOWN
refuses rather than pretending there is no more history.

Selection and each navigation get non-wrapping monotonic tokens. Requests for superseded
selections/windows become ineligible. Message keys are `(chatId, messageId)`. Edits, removals
and local expiry invalidate affected pending history, including edits of not-yet-cached IDs.
This uses bounded request invalidation, not an unbounded tombstone/revision map. Duplicate NEW
arrivals do not act as edits or increment the incoming indication. New text is inserted at
latest; an older displayed page retains its rows and gets an indicator capped at 100.
Mutation can leave continuation UNKNOWN: explicitly reload latest for a fresh continuation.

Refresh supersedes the old directory/history and reloads the directory. It reselects the
previous chat only if the fresh retained directory still contains it as a supported user chat;
otherwise selection remains empty. The process facade waits for fixture READY before issuing
the fresh directory request. Ordinary offline state retains data but refuses new reader
requests; revocation and Close purge private application views and selection metadata.

Bounds remain 256 ingress / 8 pending / 16 writes / 100 retained chats / 100 retained messages
**globally**, with 20-chat/10-message pages. Text/sender projection uses Unicode-safe UTF-8
prefixes and an explicit truncated flag; oversized titles/display names get an ellipsis.
Unsupported content projects to a placeholder without fetching anything. Diagnostics remain
redacted. This does not bound TDLib allocations or normalize persisted native values.

## Typed wire table

Header size stays 45 bytes: magic[8], kind[u8], payload length[i32], generation[16],
sequence[i64], request ID[i64], using the existing big-endian encoding. V1 magic TGCBRG01
retains its original exact schemas. V2 is **TGCBRG02**; it is explicitly selected at construction,
not negotiated or silently downgraded. Mixed typed versions in one session are refused.
TGCPIP01 fixture control and TGCAUT01 private PUBLIC-input transfer stay separate and unchanged.

V2 shares only Control(1), Auth(2), directory Request(3, target CHATS only), Connection(8)
and Failure(9) with v1. Legacy Identity/Chats/Messages/Removed/Changed are not v2 payloads.
New payloads have exact fields, duplicate/trailing/type checks and bounded collections:

| Kind | Exact body fields |
|---|---|
| Account (11) | `userId`, `displayName` |
| HistoryRequest (12) | `chatId`, `selection`, `window`, `cursor`, `limit` |
| HistoryPage (13) | `chatId`, `selection`, `window`, `rows`, `continuation`, `cursor` |
| MessageUpdate (14) | `update` (NEW/EDIT), `message` |
| ChatRemoved (15) | `chatId`, `ids`, `expired` |
| Directory (16) | `rows`, `continuation`, `cursor` |

Directory rows retain `id`, `title`, `chatKind`. Reader message objects contain `chatId`,
`id`, `sender`, `text`, `date`, `expiresAt`, `edited`, `truncated`, `content` (TEXT/UNSUPPORTED).
A page may contain only its declared chat, without duplicate message IDs. Continuation is
AVAILABLE with a positive opaque cursor, or END/UNKNOWN with cursor zero. History-request
cursor zero means latest; a later request uses the stored continuation within its selection.
The PUBLIC fixture uses message offsets internally; that is not an adopted TDLib cursor map.

New payload limits are Account/HistoryRequest/ChatRemoved 4096 bytes, MessageUpdate 16384,
Directory 65536 and HistoryPage 262144. Page size/text limits remain separately enforced.
Application/G1 storage identity and G1's separate 112/120-byte record are unchanged.

## Verification and remaining boundary

`OrdinaryReaderTest` covers protocol/model, identity, superseded requests, incoming anchors,
causal invalidation/expiry, cancellation/deadlines, revocation, bounds and projection.
`OrdinaryReaderSessionTest` uses the same facade against in-memory and actual PUBLIC child
peers, including v2 process updates, refresh/fixture Close and refusal of a legacy typed peer.
The existing v1 regression tests remain in place.

With cached dependencies and JDK25, the focused command is:

```sh
./mvnw -o --batch-mode --no-transfer-progress '-P!native' \
  -Dtest=OrdinaryReaderTest,OrdinaryReaderSessionTest test
```

Recorded validation: the initial full SDK-free run executed 276 tests with zero assertion
failures and one error: the nominal transport Close timed out. All 14 new P1 tests passed.
The PUBLIC peer had announced HELLO before constructing/encoding its initial typed state;
that real preparation now occurs before HELLO, with initial typed bytes still emitted after
it. No dummy warmup, deadline increase or shutdown-check relaxation was added. The original
trace lacks phase telemetry, so its exact runtime delay is not proven by this source finding.
After explicit approval, one focused post-fix run passed all 38 tests across the ordinary
reader, PUBLIC transport and existing synthetic/process session suites, with zero failures,
errors or skips. The original failed workspace is retained; the full suite was **not rerun**
and is not reported as a full green run. Both checks used fresh SDK-free source workspaces,
cached JDK25/Maven and offline dependencies; the existing development JAR was preserved.

These are application/PUBLIC evidence only. `nativeStopAccepted()` remains false even after
clean PUBLIC process closure. Local disposal, fixture acknowledgement/EOF/zero exit and the
preview budgets do not establish genuine native stop, lease release, G1 readiness, account
consistency or session reuse. A1–A6/A8–A10 in the
[activation register](read-only-activation-register.md) still govern genuine integration.
No SDK build, actual TDLib engine, real provider/account or send is enabled by P1.
