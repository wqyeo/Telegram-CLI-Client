# Ordinary-conversation shared reader: contract proposal

Status: the [P1 PUBLIC implementation](ordinary-reader-p1.md) now supplies the application
reader subset and its exact v2 wire table. **Native activation and the genuine-authority
parts below remain unimplemented.** This document retains the broader intended contract;
working names are not a claim that every genuine integration field/API has shipped.
Original proposal inspection baseline: `a313c00`; see the
[activation-blocker register](read-only-activation-register.md) for remaining blockers.

## Reuse the existing bridge core; do not start another engine abstraction

Recommend evolving `BridgeData` + `BridgeProtocol` + `BridgeCoordinator` and the existing
read-only session seam. Keep one ordered state owner for both CLI and Lanterna. Leave the
original `TelegramEngine`/`EngineEvent` demo path intact; adding a parallel real-reader
reducer there would duplicate the preview's existing auth/request/lifecycle machinery.

`ReadOnlyPreviewSession` and its implementations are explicitly fixture-only today. Preserve
that public safety boundary. A future genuine launcher/factory must be separate and guarded;
renaming `attachPublicFixture` or accepting an arbitrary Process is not real activation.
A small facade can share operations/snapshots while fixture construction and genuine
construction remain distinct. Production TDLib classes remain inside the worker adapter.

## Proposed first product capabilities

Recommended minimum for the first controlled milestone:

- List known conversations in bounded pages; filter the **currently retained loaded list**.
- Open an existing ordinary one-to-one conversation and view text messages.
- Load an earlier page or return to latest; receive selected-chat new/edit/delete/expiry updates.
- Refresh, cancel a logical request and Close honestly.

P1 selects this PUBLIC application scope explicitly; it does not implement groups or
server search. Unsupported chat/content capabilities must be explicit. Loaded-only search
must not claim a complete account-wide or server search; paginating the directory may evict
older retained entries under the existing bound. No result means 'not in loaded results',
not 'Telegram has no such conversation'.

No send/reply/edit/delete/reaction/logout/read-marking command or arbitrary TDLib function is
exposed. Opening a conversation is an application selection operation, **not automatic
permission to call TDLib openChat/viewMessages or mutate remote read state**. A6 must map
native operations and effects explicitly before genuine use. Authentication/mandatory native
traffic is not falsely advertised as zero-server-effect access.

## Operation algebra and ownership

An illustrative facade supports the operations below; it does not own the worker's profile
lease or mint native admission/stop authority.

| Operation | Application input | Correlated outcome |
|---|---|---|
| Request directory / next page | Opaque directory continuation and current directory scope | Bounded chat page, explicit continuation status, request failure or cancellation |
| Search loaded directory | Bounded local query | Local filtered snapshot + LOADED_ONLY coverage; no native/network command |
| Select conversation | Nonzero signed chat ID from an allowed directory capability | New selection token and initial-history request; explicit unsupported/not-available result |
| Load earlier / latest | Current selection and bounded opaque history continuation | New history-window token and correlated page outcome |
| Cancel request | Issued application request ID | Local ineligibility of its result; not proof of native cancellation or resource stop |
| Refresh | Current directory/selection scope | Explicitly superseded requests and fresh requests, not blind replay of auth input |
| Request Close | Current session | Immediate publication revocation/private-view disposal, followed by separately observed shutdown disposition |
| Submit supported auth step, in the later genuine facade | Owned AuthSubmission for current challenge token | Correlated prompt/error; no input retained in ordinary snapshot or JSON |

Keep `AuthSubmission`/`AuthTransfer` as the private input channel. Legacy fixture
`advanceAuthorization()` continues to select PUBLIC bytes only; it must not become an
operational credential input method. Failed/unknown auth delivery never automatically
resubmits a code/password or rearms a consumed token.

## Identity model: resolve the current Saved-only ambiguity

| Proposed value | Purpose / rule |
|---|---|
| Session generation | Existing UUID, valid for one worker/session. Reject wrong generation before state mutation. Reopen gets a fresh generation, not stale requests. |
| Request ID + frame sequence | Existing bounded ordered protocol identities. No wrap/reuse; overflow refuses. Sequence validity and request correlation remain separate checks. |
| Account identity | User ID + display projection. Do not require or create Saved Messages merely to become ready; current `Identity.savedChatId` must cease being universal session authority. Native identity must match the accepted authenticated store identity. |
| Chat ID | Signed nonzero value; negative group IDs must not be accidentally invalidated by a positive-only future type, even when a capability is unsupported. |
| Selection token | Generation-bound monotonic identity changed on every selection, including reselecting the same chat. Prevents late A replies replacing B. |
| History-window token | Changed for each navigation within one selection; prevents a late earlier page replacing a newer latest/earlier selection. |
| Message key | `(chatId, messageId)`, never message ID alone. Pages, new/edit/delete/expiry events all carry chat identity. |
| Content revision | Local causal invalidation identity for cached-chat changes; pending pages record it at issuance. Not a server snapshot version or G1 storage revision. |
| Continuation | Opaque, bounded and bound to directory/selection/window as appropriate. UI does not manufacture SDK offsets or infer a complete cursor from a message ID. |

Neither application IDs nor TDLib query IDs authorize G1 readiness. Native admission attempt,
borrowed snapshot stamps and final same-actor seal rules retain their separate authority.
No frontend command may assert 'native admitted' or 'native stopped'.

## Snapshot and event delta

Keep immutable redacted snapshots with explicit fields for:

- Lifecycle/authority: starting, auth-needed, admitting, usable, closing, failed/uncertain,
  closed; distinguish connection availability from storage/auth authority.
- Current auth challenge metadata, account identity and supported capabilities.
- Bounded directory and search coverage/status.
- Selected chat/token; displayed history-window/token; retained-message count.
- Loading/error/continuation state for directory and history independently.
- New-message indication while browsing older history.
- Close progress and evidence layer; PUBLIC fixture closure cannot be displayed as genuine
  engine-stop acceptance.

Auth READY, pipe HELLO, identity delivery and network READY are not sufficient for USABLE.
The genuine worker must have passed the G1 authority boundary. That trust comes from the
reviewed native/worker integration, not a newly added caller-controlled Boolean. No
specific native acknowledgement signature is selected by this proposal.

Typed data events need directory pages, scoped history pages, new messages, edits, removals/
expiry, capability/connection/auth changes and request failures. A new-message event must
insert a previously unseen selected-chat key; current Changed only updates cached IDs and
cannot provide this behavior.

## Ordering, paging and invalidation rules

1. One ordered consumer owns all mutations. Validate generation, frame ordering and payload
   shape before touching state. A valid but superseded reply consumes no current view request.
2. Retain request context: operation, chat/selection/window where applicable, continuation and
   local content revision. Type/target mismatches are protocol failures, not silently accepted
   pages. Replies for old selections cannot populate the new selection.
3. Display a bounded page in stable date/message-ID order; deduplicate by message key. Retain
   only the bounded cache, not an ever-growing history or tombstone set. A page interface is
   enough for this milestone; no infinite-scroll framework is required.
4. New/edit/delete/expiry events invalidate relevant in-flight page eligibility before those
   pages can restore old text. Apply removals/expiry to all retained representations, not only
   currently visible rows. Preserve newer known update content over an older pending page.
5. At latest, show selected-chat incoming text within the window bound. While viewing earlier
   history, keep the anchor and expose an incoming indication rather than jumping the view.
   History navigation still advances its own token.
6. A short/empty page is not automatically certified end-of-history. Represent continuation
   as AVAILABLE, END or UNKNOWN; the native adapter must justify END from its mapped operation.
   Cancellation/timeout is not END. Directory continuation also cannot assume chat IDs encode
   stable ordering during live directory changes.
7. These rules prevent local stale-result mistakes, not arbitrary stale server/SDK snapshots.
   A6 must establish update/history mapping and use refusal/resync where native freshness is
   uncertain. Local revision counters are not a new account-consistency guarantee.
8. Auth/storage revocation clears private data, requests, selections and usable authority.
   Ordinary offline connection loss is a distinct state, not an instruction to delete/recreate
   the store or automatically authenticate. Close remains available during loading/failure.

## Bounds, privacy and protocol compatibility

Preserve the existing bound envelope initially: 256 ingress entries, 8 pending requests,
16 queued writes, 100 retained chats and 100 retained messages globally, 20-chat/10-message
pages. Extending dimensions
must not multiply these into an unbounded per-chat cache. Selection metadata is bounded too.
These are application/fixture bounds, not admission domains for persisted native values.

Native text/titles can exceed current display DTO byte limits. Before private encoding,
project to an explicitly marked truncated display value without splitting Unicode encoding;
do not fail an otherwise valid live session merely for a long ordinary message or silently
pretend its prefix is complete. Unsupported content is a marked placeholder, not a media
fetch. Projection is not normalization of stored/native data, nor a claim to bound TDLib's
own allocations. Keep ordinary toString/Jackson diagnostics redacted.

The current TGCBRG01 codec requires exact fields and has no chat scope on message payloads.
Changing those meanings is not backward-compatible. Recommend an explicit revised typed
bridge version for the new shape, with strict dispatch to the appropriate decoder and no
silent downgrade or old-message reinterpretation. Keep legacy PUBLIC fixtures recognizable;
reject mixed typed versions. The subsequent [P1 wire table](ordinary-reader-p1.md#typed-wire-table)
records TGCBRG02 and its exact fields alongside preserved legacy TGCBRG01. G1's separate 112/120-byte storage record is unchanged.

Existing preview request/auth/transport budgets must not become production G1 readiness or
native-stop timeouts by accident. Logical expiry removes publication eligibility, not native
resources; failure/forced process death cannot manufacture clean Close or lease release.

## P1: implemented PUBLIC application subset

The implementation evolves these existing locations; native authority is not included:

| Existing location | Necessary delta |
|---|---|
| `session/bridge/BridgeData.java` | Typed operation/context, chat-scoped message/event values, explicit capabilities/continuations and bounded display projection. |
| `session/bridge/BridgeProtocol.java`, namespace dispatch in `BridgePipeProtocol.java` | Explicit revised private codec; reject incompatible/mixed frames rather than reinterpreting v1. |
| `session/bridge/BridgeCoordinator.java` | Selection/window/request context, composite keys, incoming insertion and scoped causal invalidation. Preserve ordered state and bounded failure behavior. |
| `application/ReadOnlyPreviewSession.java`, synthetic/process implementations | Shared ordinary-reader facade plus a legacy fixture adapter; remove unconditional Saved loading from the new flow without deleting the old demo. |
| `application/SyntheticBridgePeer.java` and existing test fixtures | A small PUBLIC two-chat dataset with overlapping numeric message IDs and controllable late responses/updates. No second demo application, launcher or genuine provider. |

Do not add a production CLI/TUI redesign, generic SDK dispatcher or native factory enablement
in P1. P3 connects the real facade and existing frontends after the necessary backend gates.

Focused acceptance cases belong in existing coordinator/protocol/session test locations:

- A and B share a numeric message ID but never share message state.
- Select A then B; late A and stale-window replies cannot replace B/current navigation.
- Earlier pages deduplicate; incoming new text is inserted at latest without moving an older anchor.
- Edit/delete/expiry invalidate pending pages and cannot be undone by those replies.
- UNKNOWN continuation, cancellation and offline/error do not falsely report history end.
- Revocation/Close purges every retained private view; logical disposal is not native stop.
- Bounds, Unicode projection/redaction and incompatible wire versions fail/behave explicitly.
- Legacy fixture-only entry points remain fixture-only; no real provider/native loading occurs.

The original proposal added no tests. The subsequent P1 code adds focused ordinary-reader
core/protocol and shared PUBLIC session tests; their commands and evidence boundary are
listed in the [implementation note](ordinary-reader-p1.md). Historical counts do not prove
current native functionality.

## Remaining integration choices

P1 **evolves the existing bridge, supports known one-to-one text chats with loaded-directory
search, versions the changed typed payloads explicitly, and preserves legacy PUBLIC fixtures**. Broader chat/server-search capability may be
chosen, but needs additional adapter/effect/cursor scope before coding.

P1 retains SDK-free defaults and the native factory fence. Further fixture/UI expansion is
not a substitute for a bounded native integration package. A1–A6 remain genuine activation
blockers; P1 is product plumbing toward the read-only journey, not a workaround for them.
