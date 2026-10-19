# Read-only Telegram client: activation-blocker register

Status: **implementation-facing register; no real activation or revised policy approved here**.
Baseline: application commit `a313c00`. Inspection of the application/bridge sources and the
existing G1 findings establishes the gaps below; no new native investigation or runtime was
performed. The [shared-reader proposal](shared-reader-contract-proposal.md) specifies the
smallest application-side implementation package. The subsequent
[P1 PUBLIC implementation](ordinary-reader-p1.md) supplies that reader plumbing; it does
not close the native or genuine frontend/activation rows.

## Product outcome and authority

The milestone is one Linux personal-account session:

**explicit profile → local unlock/authentication → find/open an ordinary conversation →
initial/earlier history → incoming update → honest Close → authenticated same-profile reopen.**

The CLI and existing Lanterna frontend use one ordered reader core. A fixture, Saved Messages,
a successful SDK build or another source packet does not complete this journey. Sending stays
disabled. Unsupported authentication flows must stop explicitly, without accepting registration,
terms, payment or another device's authorization on the user's behalf.

The governing storage policy is the [approved G1 contract](native-g1-contract.md), including
healthy maintenance, binlog-only mode, filesystem-bound identity and raw32 keys. The older
[native-readiness memo](native-readiness-decision.md) is historical recovery evidence, not a
reason to reopen the already selected G1 direction. The [source checkpoint](native-g1-checkpoint.md)
remains experimental: `integrated_engine_accepted=false`; the factory remains fenced.

## What is reusable, and what is not ready

| Current code | Reuse / concrete limitation |
|---|---|
| `session/bridge/BridgeData.java`, `BridgeProtocol.java` | Bounded, redacted app-owned data and explicit codec. Legacy v1 stays CHATS/SAVED_MESSAGES. P1 v2 adds ordinary history, account identity without Saved, scoped updates and explicit continuations. |
| `session/bridge/BridgeCoordinator.java` | Ordered generation-scoped ingress, auth tokens, pending requests, expiry/revocation and bounded caches. P1 v2 adds composite keys, selection/window tokens, bounded causal invalidation, loaded search and new-message insertion. Genuine native authority remains outside this PUBLIC core. |
| `application/ReadOnlyPreviewSession.java`, `ProcessReadOnlySession.java`, `SyntheticReadOnlySession.java` | Existing shared frontend seam and PUBLIC implementations. Legacy construction still targets Saved. Explicit ordinaryReader factories load a directory and support user-chat selection/history through the same facade. Auth advancement remains generated PUBLIC bytes. |
| `session/bridge/ProcessBridgeTransport.java` | Bounded PUBLIC pipe lifecycle with ack/EOF/process/readers. Its fixture handshake and clean flag do not establish genuine native stop. |
| `telegram/TelegramEngine.java`, `model/EngineEvent.java` | Original demo event seam. It does not express discovery/history/auth/stop; do not build a second ordinary-reader state machine around it. |
| `session/ProfileStore.java` | Sole Java lease, retained directory identity and native-live claim primitives. Genuine native-stop/worker/lease orchestration is not joined. |

Paths in this table are relative to `src/main/java/com/gitlab/wqyeo/telegramcli/`.

## Finite blocker register

These are ten work items, not ten claims that the underlying native work is small. A row is
closed only by its specified implementation/evidence, not by producing another document.

| ID / accountable boundary | Current blocker and product consequence | Closure deliverable / evidence |
|---|---|---|
| **A1 — Native admission coverage** | Supported persisted schemas, original-state/replay/effect dispositions and broader initializer families are not closed. The [five-role candidate](native-g1-fixed-roles.md) now has joined source guards and bounded compilation/PUBLIC component evidence, not actual-phase acceptance; 120 other integer contracts remain open. Binlog-only/UI deferral does not exclude native parsers or service effects. Unsafe to open an account store. | A finite supported-state/family/phase register; necessary D1/D2/D4 decisions; complete guards or enforced compatible exclusions for every reachable row. Preserve identity/exact24/main-DC/raw32/no-repair rules. Reuse inventories; no one-option-at-a-time leaf campaign. |
| **A2 — Native readiness/completion integration** | Real required-owner completion, value/attempt binding, observed snapshots/wakes and outward publication remain unwired. Callback/Unit/disposal/restart markers are not service success. | Select D3 from a concrete required-work roster without introducing repair/reload cycles; implement combined validators, both seals, semantic-refusal precedence, same-actor final validation and one-flight/wake handling. Joined PUBLIC checks, then E2 evidence; no mechanical descriptor bump. |
| **A3 — Runtime authority loss** | Storage/auth loss must revoke operations and private views; durable revoked-state/restore behavior is unfinished. A disconnected network is not the same as revoked authority. | Native/application revocation path, pending-work disposition, private-view purge and refused unsafe reopen. Show loss at relevant lifecycle phases without reset/repair/automatic reauthorization. This is logical data disposal, not a claim of perfect erasure. |
| **A4 — Stop, sole lease and reopen** | FileLoad/retry/GC/transfer descendants, shared native implementation ownership and actual worker stop are not composed. Current PUBLIC close success is insufficient. | A closed reachable resource roster and genuine generation/profile-bound positive stop observation; compose with ack, EOF, zero exit and readers before permitted lease release. Uncertain Close retains honest ownership; kill/timeout/removed handle is not clean flush or stop confirmation. E2 must exercise actual resources and same-store reopen. |
| **A5 — Privacy and local input** | Genuine worker launch/input/log containment is not joined; native log/fatal/IPC/temp surfaces remain unresolved. | Audit only the reachable launch/auth/read/close path; install containment before sensitive initialization, fixed/redacted errors and bounded private IPC, local credential acquisition, best-effort owned-buffer wiping, no private account content in evidence. Do not repurpose fixture auth advancement for real secrets. |
| **A6 — Genuine guarded worker/adapter** | No operational adapter connects the trusted lease/context to real TDLib auth/read requests and the shared core. | Explicit NEW versus EXISTING worker orchestration, lazy genuine provider, one-use native claim, closed allowlist of typed operations and SDK-to-app translation. No arbitrary TDLib-function gateway; worker sharing/ownership must satisfy A4. Keep the factory fenced until all applicable native gates pass. |
| **A7 — Ordinary shared reader** | P1 PUBLIC code now expresses ordinary selection/search/history/updates in the existing core; this is not an operational account client. | The [P1 implementation](ordinary-reader-p1.md) and its focused SDK-free/PUBLIC checks cover the application subset. Genuine adapter semantics and CLI/TUI consumers remain A6/A8; no separate reducer or live-use acceptance is inferred. |
| **A8 — Auth and frontend integration** | Fixture prompts are not operational login; no ordinary account-backed CLI entry point exists. Existing state labels do not encode native admission/actual stop. | Map supported auth prompts and errors without resubmitting consumed secrets; expose truthful activation/close phases and the reader operations. CLI first, then the existing TUI; no redesign or auto-acceptance of unsupported flows. Depends on A5–A7 and native authority gates. |
| **A9 — Native acceptance evidence** | Source/leaf checks are not a coherent SDK or actual engine. The original descriptor6 reserved-gap failure is UNDETERMINED. | Explain that failure or obtain an explicit residual disposition before the relevant acceptance gate. Separately approve E1 build/link, then E2 disposable PUBLIC actual-engine checks. Failures remain blockers; no default SDK replacement, image pull or fixture pass relabeled engine acceptance. |
| **A10 — Genuine read-only journey** | No real authentication, ordinary history/update or authenticated reopen has been demonstrated. | After A1–A9 and explicit F approval, operator-local acceptance of every journey step above. Record redacted outcomes only. Failure does not authorize reset, fresh login fallback or sends. |

## Critical path, not an implied activation estimate

The operator permitted the fenced five-role source increment before the remaining A1/A2
prerequisites. Its descriptor9 candidate includes original/import/default/runtime/consumer
and both-seal placements, with selected semantic refusal before healthy Changed. Eleven
native objects plus private Java compiled; PUBLIC14 context and 55 value families passed.
This sequencing change does not select D3 or actual-stop ownership, close broader admission,
or authorize E1/E2/F. The factory remains fenced.

A1, A2 and A4 are the dominant native critical path. **A proved minimal safe native activation
slice has not yet been established.** The open integer/schema/effect families must be grouped
into compatible validation/ownership families, not converted into 120 more isolated source
milestones. A5/A6 then have to connect privacy and the genuine worker to those boundaries.
P1 is a bounded, necessary application delta implemented independently with PUBLIC inputs; after it,
additional fixture/UI expansion is not the default response to blocked native activation.
No delivery date or claim that only an SDK build remains is justified by this register.

## Supported-slice recommendations, not silent exclusions

The proposal recommends the smallest first product surface: known ordinary one-to-one text
conversations, loaded-directory search, explicit earlier pages and selected-chat updates.
Other chat/content types must be represented as unsupported where necessary, not misparsed
as text or used to claim a complete client. These recommendations do **not** remove native
initialization/replay obligations from A1/A2. P1 selects these capabilities explicitly;
broader conversation/search and native operation mappings remain separately scoped.

Read-only means no exposed send/reply/edit/delete/reaction/logout/read-marking command. It is
not a promise of zero server-side effects from authentication or mandatory TDLib traffic.
The adapter's operations, subscriptions and any open/close-chat behavior require an explicit
effect classification in A1/A6; a method named 'open' is not automatically approved.

## What can be deferred

| Deferred product work | Enforced boundary needed |
|---|---|
| Outgoing messages/replies, reactions, user edits/deletes, logout | Absent from command algebra and native allowlist; pending replay/service effects still reviewed under A1. |
| Media download/upload/playback and broader conversation types | Explicit unsupported capabilities; no accidental media helper call. Reachable initialization and file-reference work still belongs to A1/A2/A4. |
| Full SQLite caches, imported stores, migration/key rotation, relocated restore | Preserve existing G1 mode/admission refusal. No flags-only shortcut, adoption or silent rebuild. |
| Windows, packaging/release/wiki/CI expansion, visual redesign | No work required for this Linux vertical slice; existing defaults/artifacts remain intact. |

No privacy, stop, lease or original-state guarantee is deferred merely to reach a live demo.

## Implementation order and stop conditions

1. **P1: ordinary-reader contract/core delta (A7).** Use existing fixtures and core; no new demo,
   provider, native launcher or SDK dependency. This PUBLIC application subset is implemented;
   it enables the journey to be represented, not a claim that it is live.
2. **P2: native activation package (A1–A5).** Convert existing findings into exact guard/owner/
   stop placements. Investigate only a named unresolved edge whose resolution changes those
   placements. If no bounded safe slice can be established, stop and report the guarantee/scope
   trade-off; do not keep expanding source packets or weaken policy.
3. **P3: guarded adapter and CLI (A6/A8).** Wire the shared operations behind the closed factory,
   with PUBLIC providers in checks. Connect the existing TUI to the same state after CLI behavior
   is sound. Real auth inputs remain unavailable before the genuine-use gate.
4. **E1 → E2 → F (A9/A10).** Each is separately approved. Ordinary conversation/history/update/
   clean-close/reopen evidence, not Saved Messages alone, completes the milestone.

Application work may proceed while native activation is blocked, but never justify readiness
or an account test by pointing to P1. Do not spend further work on UI expansion while A1–A5
lack a bounded integration path.

## Investigation budget is expressed as closure, not a new timer

Every proposed native investigation must identify one register row, the exact unanswered
question, fixed inputs, expected implementation consequence and stop condition. Current
FileManager preprocessing has a prepared host-input case, not execution approval. Its four
functions, the full-local helper and scheduler-finish gap are distinct scopes. Do not add
translation units or relax syntax refusal to obtain a green report.

Production readiness polling, retry caps, fairness and finite completion guarantees remain
unselected. Existing fixture/request/transport budgets do not establish them.

## Immediate handoff

The register and contract proposal complete the first planning deliverable only. They do
not remove a blocker by themselves. The next code-sized increment is P1, with changes and
acceptance cases named in the proposal; no further generic native source inventory is the
default next action. Native/account execution, sends and release work remain gated.
