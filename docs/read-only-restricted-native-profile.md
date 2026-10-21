# Restricted native reader profile — bounded design assessment

Status: **design direction selected; feasibility NOT established; activation NO-GO**.
Baseline: `5093ff3`. This is the operator-approved bounded design pass after the
[activation decision](read-only-activation-contract.md#source-disposition-and-activation-gono-go),
not an implemented mode, selected D3/stop API, or permission to run another compiler campaign.
The factory remains fenced; `integrated_engine_accepted=false`.

## Objective and unchanged guarantees

Retain explicit-profile personal authentication, ordinary one-to-one conversation discovery,
initial/earlier text history, incoming changes, honest Close and authenticated same-profile
reopen. Reuse the P1 ordered reader core, CLI first and existing TUI later. No send or
read-marking commands. Retain the selected unsolicited presence/default-reaction/top-peers
prohibitions and no automatic acceptance of registration, terms, payments or another device's
authorization; other native effects still require classification.

All [G1 guarantees](native-g1-contract.md) remain: explicit pristine NEW, invalid EXISTING
refusal without repair/reset/adoption/key fallback, approved healthy maintenance, binlog-only
mode, raw32 keys, filesystem binding and sole Java lease until positive actual-resource stop.
An exclusion is not a blanket syscall veto or a new rollback/finite-completion guarantee.

A non-text item must be represented as unsupported only if a safe native metadata path is
established; otherwise fail the operation explicitly. Do not silently drop messages and call
the history complete. A profile that cannot support the ordinary-reader journey is not a
successful smaller implementation merely because it avoids risky work.

## Proposed owner partition — no exclusion is yet proved

“Retain” describes a product necessity, not accepted transitive implementation coverage.
“Exclude target” requires a demonstrated native boundary, not a removed frontend command.

| Owner/work | Proposed disposition | Concrete unresolved dependency |
|---|---|---|
| TdDb, authority PMC/config, OptionManager | Retain guarded storage and only explicitly supported option behavior | OptionManager imports/decodes the whole config map and creates defaults before ordinary use. A five-role whitelist alone cannot make the other 120 options inert. |
| AuthManager, self identity, primary key/DC/session transport | Retain personal auth and current authority | NEW and EXISTING have different early Ready producers. Session/key writes, self/mirror writes and outward authorization are not one completed barrier. |
| Messages/User/Dialog state and UpdatesManager | Retain ordinary directory/history and incoming updates | Contact/presence, dialog state and update-state reads share these owners; removing unrelated managers does not remove their persisted-input obligations. |
| ConnectionCreator, dispatcher and necessary network configuration | Retain required transport/configuration | DC/proxy/config readers and fallbacks remain even if themes and transfers are absent. Optional AppConfig refresh must be separated from necessary configuration, not disabled wholesale by assumption. |
| Default folder setup versus custom dialog-filter reload | Retain necessary local setup; exclude optional reload only if separated | Messages authorization calls local folder work before dialog filters; no proof currently isolates that work from its descendants. |
| FileManager metadata versus FileLoad, upload/download/generation and Storage/GC | Metadata path conditional; transfers and unnecessary file work are exclusion targets | FileLoad is constructed on a slow-net scheduler, and metadata/replay paths can retain file owners. “No download command” does not prevent their creation or disposal callbacks. |
| Online and setting-changing reaction/top-peers work | Product prohibition already selected | Native initiation, pending intent and runtime producers still need exact rules. Sending offline is not passivity; rejecting a callback after initiation may itself request restart. |
| Reaction/Stickers and notifications/settings | Split optional services from any mandatory message handling | Incoming message representation and notification handling may reach these owners. Entire managers cannot be declared unreachable from their frontend feature names. |
| Themes, promo, attach-menu, browser/translation and other optional services | Exclude-target startup/refresh/persistence paths | Existing post-auth roots initialize these independently of exposed commands. Simply omitting a root call does not prove constructor, replay, update or destructor paths absent. |
| Secret chats, outgoing/media and other unsupported work | Preserve refused/unsupported product boundary and prove native exclusion | Flags and command algebra are not transitive replay/constructor/state exclusion. Do not discard pending intent or synthesize successful completion. |
| ClientManager, scheduler/shared implementation and JNI receiver | Retain controlled ownership until genuine stop | The later [owned-domain decision](read-only-activation-contract.md#selected-owned-domain-direction) selects a G1-owned manager and joinable receiver. Resource coverage, normal joins, ExitGuard handling and generation/profile-bound positive stop remain unproved. |

The existing fourteen post-auth roots and 99 persisted-family candidates are worklists, not
an exhaustive native owner/state universe. This partition must not convert their unknowns
into an allowlist by omission.

## State frontier — not a new allowlist or absence policy

| State group | Proposed profile treatment | Original/runtime contract still needed |
|---|---|---|
| Personal authority, key/DC, self/mirror and retained salt/time state | Retain current selected G1 predicates | Preserve original validation and both seals; close every relevant producer and authority-loss path. Local key consistency is not server authorization. |
| Entire imported config map, exact24 and five fixed roles | Preserve current guards; do not assume only five values are consumed | Either close each retained value/default/dynamic writer or prove exclusion before import, default creation and dependent consumers. No exact-default shortcut for mutable server limits. |
| `dc_options`, proxy families and required configuration; optional `app_config` | Retain necessary transport subset; optional refresh is an exclusion target | Separate actual formats, fallback and cleanup paths. Core transport cannot be declared absent with the unused services. |
| `updates.pts`, `updates.qts`, `updates.date` and required dialog/user state | Retain incoming-update necessities | Explicit missing/present/cross-field, writer, discovery and replay rules. Disabling secret chats does not by itself remove a shared qts reader. |
| `tmp_file_id`, `perm_file_id`, `fast_file_stat`, `files_gc_ts` and related file state | Exclusion targets only where non-use is proved | Metadata versus helper creation is not separated; no inferred counter/size/path domain or blanket file-prefix policy. |
| Setting intent and optional service caches | Refuse incompatible intent; exclude unused readers/producers only with proof | Two [exact marker rules](read-only-activation-contract.md#selected-pending-marker-admission) are now selected. Related schemas, cache membership and runtime enforcement remain unresolved. No clearing, default repair or successful skipped SET. |

This table reuses the existing candidate families. It selects no new serialized values,
unknown-key allowance, opaque retention or schema/version compatibility. Pristine NEW
cannot be assumed to stay within a supported state set when native defaults and server
responses create additional values; authenticated reopen must satisfy the same profile.

## Enforcement placement required by any implementation proposal

1. Establish an immutable trusted capability choice before native initialization and before
   any sensitive logging/store export. Do not let untrusted persisted options choose their
   own validation mode. The encoding/API for that choice is not selected here.
2. Validate original accepted state before import, defaults, cleanup and output. Unsupported
   original state must refuse under an explicit family rule; no silent erasure, renaming,
   ignored intent or permissive parse fallback. Exact membership/absence rules remain needed.
3. Enforce exclusion before the corresponding native owner/work/promise is created or its
   input consumed. Do not null manager pointers, disable SQL flags or skip callbacks and
   assume callers no longer depend on them.
4. Cover indirect runtime writers, replay, updates, retry/repair and destruction-created
   continuations. The repair register shows why a failed/lost upload-repair promise can
   request a restart: delivering an error is not automatically an exclusion or stop boundary.
5. Keep a closed typed adapter operation set. UI selection does not automatically authorize
   TDLib open/view/read-marking calls; classify the actual native effects. Necessary transport
   traffic is not a presence RPC, and a method called “get” is not sufficient effect evidence.
6. Preserve runtime revocation/private-view disposal. An internal forbidden attempt disproves
   the proposed exclusion; it cannot be reported as a harmless successful no-op. Exact error,
   ownership and Close handling must be specified before implementing the boundary.

An unused family leaves the supported parser/work graph only after proving its reads,
writes, defaults, cleanup, replay and later consumers excluded. This does not itself select
an opaque-retention or presence policy for original bytes. Likewise, optional continuing
services remain validation, effect and shutdown obligations whenever they are retained.

## Required-work frontier — not a selected D3 roster

The following anchors locate the remaining proof, rather than declaring “all local work” done.

| Anchor | Required postcondition to establish | Why the present evidence is insufficient |
|---|---|---|
| Native context and TdDb admission | Current profile/lease/mode and accepted original state bound before consumption | Whole retained-state schema and runtime entry coverage remain incomplete. |
| OptionManager construction / `on_td_inited` | Every retained original/default/finalized value supported; refusal propagated | Whole-map import, direct default lambda and dynamic producers cannot be reduced by UI scope alone. |
| AuthManager constructor or `on_get_authorization` | Current key/DC/self/mirror authority, with value/attempt binding | EXISTING constructor and NEW callback have distinct early Ready paths; neither proves later initialization complete. |
| Messages authorization, retained update-state setup and their children | Exact finite local prerequisites for ordinary reads/updates satisfied | Folder descendants and initial update discovery versus continuing difference work are not classified to a completed owner roster. |
| `Td::process_binlog_events` | Every admitted handler and required child disposed/completed correctly | Even empty event vectors can initialize work; tail-of-init or dispatch return is not acknowledgement. Reads must not silently widen the current PMC/config replay policy. |
| TdDb capture/sync/final same-actor seal and Td publication | Selected work joined with current borrowed snapshots, stamps and final semantics | Observed wakes, single-flight handling and genuine required-work observations are not joined in production. |

Every required entry still needs exact success, error, stale/value replacement, lost delivery
and Close dispositions. Callback/Unit disposal, restart, constructor return and a moved batch
cannot satisfy it. Do not wait for every refresh RPC, introduce repair/reload cycles, or infer
polling, retry limits, fairness or finite completion. With the middle anchors unresolved,
this table is expressly **not** a finite implementable D3 selection.

## Actual-stop frontier cannot be removed by feature scope

A restricted implementation would have to demonstrate either non-creation or complete
ownership/stop of FileLoad and helper FDs, partial-read SleepActor children, repair waiters,
transfer/generation work and GC-captured continuations. FileManager actor routing stop,
owned-object reset and descendant stop are separate boundaries. The
[repair/GC register](native-g1-repair-owner-register.md) remains applicable.

Even if those exclusions were proved, native ClientManager/shared/scheduler/JNI ownership
would remain. The derived Linux source retains normal worker joins and ExitGuard detaches;
it does not establish which route a genuine generation takes. A process boundary is not a
clean-flush observation. Logical context stop and `confirmNativeStopped()` are not their own
physical-stop proofs. The observation must be generation/profile-bound and compose with
acknowledgement, EOF, zero exit and completed readers before permitted lease release.
No new stop API, observer/counter or shutdown timeout is selected here.

## Bounded assessment result and implementation boundary

**Feasibility NOT established; checkpoint 1 remains NO-GO.** Reusing the existing records
identified concrete irreducible obligations, not a demonstrated smaller native closure:

- Whole-map option import and native defaults still reach beyond the five approved roles.
- Necessary network/update/identity owners retain unsupported state and fallback edges.
- Metadata handling has not been proved independent of transfer/retry/GC creation.
- Required local/replay completion and physical native ownership lack exact observations.

The next implementation-facing proposal would have to close those four boundaries with
explicit retained-state rules and proved exclusions. This design does not authorize their
implementation, another broad source inventory/campaign, a descriptor bump or a new API.
Any eventual changed admission semantics need the appropriate reviewed identity/schema
change; the current descriptor9 and 112/120-byte records are unchanged, with no old adoption.

No compiler, C++ parser, SDK, engine, provider/account or test-suite execution occurred for this design
pass. E1/E2/F remain separate approvals. Do not advance to checkpoint 2, replace the default
SDK-free artifact, or claim a usable client on the strength of this document.
