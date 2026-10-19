# Passive reader: activation-contract checkpoint

Status: **checkpoint 1 in progress; effect policy selected, complete activation contract
not established**. Implementation baseline: `035f9b7` (descriptor9 five-role candidate).
No native guards for the new passive-effect policy are implemented by this document.
The factory remains fenced; `integrated_engine_accepted=false`.

The operator approved incremental execution of the [read-only milestone](read-only-activation-register.md),
then explicitly selected **Passive reader** at the native startup-effect decision. This is a
contract increment, not completion of A1/A2/A4 or permission to begin an account session.

## Product boundary and selected effects

Target: one Linux personal-account profile, ordinary conversation discovery, initial/earlier
text history, incoming updates, honest Close and authenticated same-profile reopen. CLI
first, then the existing TUI through the same ordered reader core. Sending stays unavailable.
The [G1 storage contract](native-g1-contract.md) remains unchanged, including explicit pristine
NEW, refused invalid EXISTING, healthy maintenance, binlog-only mode, raw32 keys, filesystem
binding and sole Java lease ownership until positive actual-resource stop.

For this first reader, **unsolicited native presence changes, default-reaction SETs and
top-peers setting changes are prohibited**, including startup and replay paths. This does
not prohibit necessary authentication, transport acknowledgements, supported reads/update
traffic or already-approved healthy local maintenance. It is not a zero-server-effects claim.
Other native effect classes are not implicitly approved by omission.

| Concrete pinned-source path | Selected disposition | Implementation consequence |
|---|---|---|
| `OnlineManager::init` / `on_online_updated` can send `account.updateStatus` when their conditions hold | No unsolicited online **or offline** presence changes | Exclude the effect at its native initiation/dispatch boundary; do not implement passivity by sending an offline update. Account traffic alone must not be treated as proof that a presence RPC occurred. |
| `ReactionManager::init` can call `send_set_default_reaction_query` when `default_reaction_needs_sync` is true | Refuse an incompatible pending setting change; no automatic replay or clearing | Validate original state before import/consumption, prevent the native SET path, and account for runtime producers. Do not clear the flag, skip the RPC and report success, or turn lost promise disposal into completion. |
| `TopDialogManager::init` can call `send_toggle_top_peers` for nonempty `top_peers_enabled` | Refuse an incompatible pending toggle; no automatic replay or clearing | Validate original intent before consumption and prevent runtime toggle initiation. Merely setting `disable_top_chats` is not an exclusion proof: `update_is_enabled` can persist/send a toggle. |

Exact supported types/encodings, benign disabled/absent states, malformed input and runtime
producer dispositions for these markers still require explicit family contracts. This policy
does **not** silently select presence-only refusal, a boolean coercion, a blanket unknown-key
allowlist, or a new default/cleanup rule. Until those contracts and placements are closed,
the corresponding activation rows remain blocked.

## Supported-state closure table

This table identifies the implementation batches and their acceptance boundary. It is not
an assertion that the finite supported key/schema/event set has already been established.
The existing persisted-family matrix contains 99 direct/alias candidate families, separate
option/secret generic boundaries and versioned-parser candidates; it is not an exhaustive
reachable schema. Reuse those records rather than start another repository-wide inventory.

| Batch | Retained decisions / source | Still needed before activation |
|---|---|---|
| Store envelope and personal authority | Current G1 filesystem/raw32/binlog-only rules; main DC, personal ID/mirror, auth/salt/time codecs; descriptor9 metadata | Actual phase refusal, complete replay/effect handling, NEW completion and runtime-loss composition. Older descriptors are not adopted. |
| Config values | Canonical B/I/S framing, exact24 retired-name absence and five fixed-role source guards | Group the other 120 integer contracts by actual compatible semantics; retain explicit rules for every member, dynamic writer and cross-field. Defaults/clamps are observations, not adopted bounds. |
| Pending setting changes | Passive policy above | Original/runtime marker contracts and enforced native exclusions for every selected effect, including indirect callers; no dropping stored intent. |
| Versioned/local manager records | Existing family and parser worklists, including notifications, reactions, themes, browser settings, update state and network configuration | Accepted current schemas, absence/cross-field rules, all relevant readers/writers and cleanup/replay dispositions. No permissive-parser/defaulting fallback accepted by inference. |
| Initialization and replay | Existing NEW/EXISTING traces and fourteen post-auth manager roots | Required-local versus refresh/repair classification and completion ownership; empty replay callbacks still need disposition. SQLite flags off do not establish unreachability. |
| Files, transfers, repair and deferred capabilities | Existing query/repair/disposal/GC registers; no media/outgoing commands | Enforced native exclusions or complete reachable initialization/resource handling. Missing frontend commands alone do not remove native owners or persisted work. |

Every retained family needs: supported values/absence, original and runtime entry points,
replay/effect disposition, pre-consumption validation or a proved exclusion, required-work
ownership if any, refusal behavior and the evidence gate. An OPEN row is not accepted input.

## Required-work obligations — D3 is not selected

The following is a candidate ownership partition for choosing D3, not a new completion API,
a selected wait-all set, or proof that every descendant is enumerated.

| Work group / source owner | Required decision and observation |
|---|---|
| Auth key/DC and self identity — Session, AuthDataShared, dispatcher, AuthManager, UserManager, OptionManager | Preserve current authority predicates; bind successful work to the current value/attempt. Setter return or a key listener is not durable publication. |
| Store import and option construction — TdDb, OptionManager | Original checks before consumption; approved default/absence behavior only; refusal propagated before later owners. Constructor return alone is not readiness. |
| Manager creation, option finalization and replay — Td initialization | Classify deferred local children, not just direct returns or empty event vectors. Establish completion/failure/Close disposition before calling a row satisfied. |
| Post-auth roots — Messages/DialogFilter, AttachMenu, Notifications/Settings, Online, PromoData, Reaction/Stickers, Terms, Theme, TopDialog, Translation, WebBrowser | Classify required finite local work, continuing refresh and enforced exclusions. The selected passive effects above cannot become required successful writes. No terms/registration/payment/device acceptance. |
| AppConfig and file-reference repair — ConfigManager, FileReferenceManager, FileManager | A reload result, config batch, repair validation, waiter settlement and requested transfer restart are different boundaries. Bind required work to its value/attempt without a reload waiting on its own repair descendant. |
| Native sealing/publication — TdDb, actual binlog actor, Td | Join the selected required roster with borrowed snapshot/stamp validation, release-before-sync, same-actor final semantics, observed wakes and one-flight handling. Busy/Changed remain not-ready. |

A future D3 decision must identify the exact required work and success observation, not just
choose “local work” as a label. Lost delivery, destruction, errors, stale/coalesced batches
and Close need explicit dispositions. Optional service work is not exempt from validation,
effect restrictions, runtime revocation or shutdown ownership. No polling, retry cap,
fairness or finite-completion promise is selected here.

## Actual-stop obligations — observation contract not selected

The sole lease remains owned until actual native resource stop is positively established
and composed with worker acknowledgement, EOF, zero exit and completed readers. None of
those transport/process events substitutes for the resource observation.

| Resource group | Observation that is still missing or incomplete |
|---|---|
| Td / FileManager object and registered actor | Actor routing stop, current handler completion, later object reset and descendant disposition composed together. |
| FileLoad and active helpers/FDs | Slow-net actor disposition and in-flight work completion; normal-path FD close alone is not universal stop. |
| Partial-read retries / released SleepActor | Pending children and destruction-created callback/dispatch attempts disposed; no assertion that timer cancellation is completion. |
| Reference repair, upload/download/generation and GC holders | Waiter/container ownership through Close, errors, lost delivery and destruction, including callbacks that request more work. |
| Native ClientManager/shared implementation and schedulers | Applicable ownership, last-owner/join and ExitGuard paths; outward Closed and removed handles are insufficient. |
| Worker/JNI receiver, pipes and parent lease | A genuine generation/profile-bound native observation composed with transport/reader completion; `confirmNativeStopped()` is a trusted call site, not its own proof. |

Process isolation can constrain ownership but does not turn kill, timeout, process death or
logical Closed into clean flush/stop evidence. No new sharing model or stop API is selected.

## Named blockers and next source boundary

The existing FileManager preprocessing proposal is a **prepared, unexecuted** source step.
Its four terminal/error whole-function gaps obstruct full repair/restart/Close dispositions.
The full-local-location helper and scheduler-finish gap are separate scopes. They are not
silently included in that invocation or resolved by unrelated compiler success.

Next proposed source action: refresh inputs against the current candidate and request one
bounded FileManager preprocessing execution under the existing containment/provenance
rules. Its question is whether the four named bodies can support the required restart/error
and disposal ownership classification. A successful preprocessing command is not a type,
whole-function, readiness, stop or engine certificate; inspect the mapped result and keep
unresolved regions excluded. Failure does not authorize another invocation or fallback.

D3, the positive-stop observation contract, remaining state rules, other effects, runtime
revocation/privacy integration and the original reserved-gap disposition remain blockers.
Checkpoint 1 closes only when the supported slice and these contracts are finite and
implementable, not when this document is published. Do not advance to coupled native
activation on the strength of this policy increment alone.

## Source and verification boundary

Pinned TDLib revision: `a94b83d67ab4c8397c6245e541b31a4131ea2f79`.
Current direct reads supporting the selected effect decision:

- `td/telegram/OnlineManager.cpp`: `init`, `on_online_updated`, `UpdateStatusQuery::send`.
- `td/telegram/ReactionManager.cpp`: `init`.
- `td/telegram/TopDialogManager.cpp`: `init`, `update_is_enabled`, `send_toggle_top_peers`.

These are source conditions, not observed account activity. Current function lookups were
empty; a scoped AST call search and direct body reads supplied the facts. No new complete
transitive graph or whole-function parser certificate is claimed. Historical inventory and
owner packets retain their original source/coverage boundaries; consumed parents are not rerun.

Public context: [five-role source increment](native-g1-fixed-roles.md),
[repair/GC owners](native-g1-repair-owner-register.md),
[disposal/client Close](native-g1-disposal-and-client-close.md), and
[prepared preprocessing scope](native-g1-preprocessing-proposal.md).

This checkpoint increment changes policy documentation only: no native code/schema/wire,
SDK/default artifact, production UI, build/test/preprocessor run or account operation.
E1 coherent SDK, E2 actual PUBLIC engine and F genuine acceptance remain separate approvals.
