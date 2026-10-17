# TDLib fail-closed feasibility — Batch A

**Result: NO-GO for activation under the unchanged strict policy. CONDITIONAL GO only for
native hardening engineering after a precise maintenance/recovery contract is approved.**
A supported sufficient control was not established; a safe, bounded implementation has
not yet been proved. This is a source/design result, not a native runtime certification.

Batch A authorizes read-only source/upstream investigation and this decision package only.
No application/native code, dependency/pin or recovery policy was changed; no native build,
database/client execution, genuine provider/credentials/account access or sending occurred.
Existing milestone1 code is preserved locally and remains uncommitted/unpublished.

## 1. What this adds to the previous decision

[The earlier readiness decision](native-readiness-decision.md) identified native recovery
mutations. This report assesses supported controls, expands the change-boundary inventory,
separates guarantees, specifies the smallest credible next engineering scope and its tests.
It does not repeat the offline transport implementation or propose another demo milestone.

Newly inspected boundaries include orphan-sidecar cleanup, SQLCipher compatibility/key-
migration fallback, schema table recreation, and native authorization-loss destruction.
They make a one-handler patch or Java request allowlist insufficient for a broad no-reset/
no-delete claim. Normal binlog compaction remains separately identified, not mislabeled
as corruption repair.

## 2. Evidence and provenance

External TDLib revision `a94b83d67ab4c8397c6245e541b31a4131ea2f79`, from
`native/tdlib.lock.json`; this is NOT an application commit. The cached source archive's
SHA256 matches the lock (`d6aa05f0121cb1e94314ab50e99a4b47e5400aa1c3ca97c8ae191a5bf6f87ea7`).
All18 source files in `.pi/batch-a-source-verification.json` byte-match their members in
that archive, read without extraction/execution. This establishes local lock/archive/file
consistency, not independently authenticated upstream provenance or actual file behavior.

Official web documentation and issue discussions were fetched during this batch. They
are live/unversioned or historical context, not guarantees about every TDLib release.
Primary operational evidence below is the pinned source, linked in section9.

## 3. Supported-control investigation

| Surface checked | Finding | Why it is not a sufficient supported solution |
| --- | --- | --- |
| Pinned `setTdlibParameters` / `TdDb::Parameters` [S1,S2] | Test DC, paths, key and optional persistence flags; no recovery/read-only policy field in the inspected interface. | Flags promote message→chat→file; disabled SQLite still has cleanup. Mandatory binlog remains. |
| Pinned `OptionManager` setter dispatch [S3] and complete fetched official options list [W1] | No sufficient no-repair/no-delete setting established. Unknown non-x options reach `Option can't be set`; x/X names are custom persistent application values. | Inventing `x_no_recovery` stores a value; it does not intercept native storage branches. A negative search is not proof no undocumented/future capability exists. |
| `use_storage_optimizer` [W1,S3] | Controls background storage optimization, not the reviewed database recovery branches. | Neither `TdDb` cleanup nor binlog load/compaction branches consult this option in the inspected paths. |
| `ignore_background_updates` [W1,S3] | Controls update handling; official docs say it does nothing when database or secret chats are used. | Not a storage recovery or corruption-preservation policy. |
| `Binlog::init` / `SqliteDb::init` [S5,S6] | Binlog opens read/write/create; SQLite opens read/write with optional create. Binlog signature has callback/key/old-key/dummy/debug callback, not recovery policy. | Lower-level create controls do not establish read-only or fail-closed client operation. The `dummy` parameter has no policy use in the inspected definition. |
| Logging controls / isolated process | Useful containment of diagnostics/crashes. | They do not alter storage mutation, nor prevent internal actor-driven destruction. |

The current official parameter reference documents invalid-key error401 [W2], not byte-
preservation of every failed open. No claim that every wrong-key attempt mutates data is
made here. Native SQLCipher compatibility fallback also must not be confused with our
application trying a blank vault/master key; the latter remains prohibited.

Two historical upstream issues contextualize the findings: #699 explains persistence flag
semantics, and #1815 reports the generic SQLite destroy/retry log after a SQLCipher linkage
error—not just corruption [W3,W4]. Neither provides a supported preservation switch. The
later #1815 linkage fix is not evidence that recovery deletion was removed.

## 4. Guarantee choices — none adopted by this report

| Contract | Meaning | Feasibility conclusion |
| --- | --- | --- |
| G0: wrapper refusal | No app reset/delete/reprovision, blank-key fallback, imported/shared DB or automatic reauthorization. | Necessary application rule; insufficient to control native behavior. |
| G1: fail-closed recovery | Refuse identified suspect/mismatched/unsupported existing stores rather than reset/repair/truncate/recreate; stop on identified runtime storage failure. Ordinary correct transactions/maintenance are separately enumerated. | Plausible hardening objective, NOT yet implemented/proved. Requires explicit permission for the ordinary-maintenance category and native lifecycle work. Not implicitly the current policy. |
| G2: literal no unlink/truncate/replacement | Even normal WAL/file lifecycle/compaction may not remove or replace native files. | No credible bounded TDLib change established. Disabling every such operation conflicts with ordinary persistence; would require a substantially different storage design or a stopped client. |
| G3: every failed open leaves all bytes unchanged | No earlier legitimate write, sidecar action or partial I/O may remain if any later stage fails. | Not delivered by removing known recovery branches. Requires a separately designed transactional/staging boundary; not a Java preflight/backup/snapshot claim, and not established here. |

**Current strict policy remains in force.** G1 is a DRAFT alternative requiring an explicit
choice, not a quiet interpretation of the existing no-delete rule. If G2/G3 are required,
keep activation blocked; this report cannot promise a small fork will satisfy them.

### Draft G1 boundaries for a future decision

- New-store creation only under explicit NEW-store intent in a pristine app-owned location;
  existing/partial/unknown stores never become NEW because one file is missing.
- Existing missing components, key/format/schema mismatches and recognized parse/storage
  errors refuse; no erase/rebuild/truncate/promote/rekey/reauthorize fallback.
- Normal validated-store transactions, WAL lifecycle, compaction/replacement, logical
  server-driven message deletion/expiry and explicitly supported version maintenance need
  an enumerated policy. They are not corruption repair merely because they mutate files.
  Initial hardening should refuse unapproved migration/key rotation instead of improvising it.
- On runtime storage/auth loss, stop application operations and purge private cached views;
  preserve local encrypted evidence under the approved contract without retaining authority
  to use revoked credentials. Native suspension/close/quarantine semantics need review.
- No promise of detecting all corruption before any legitimate write, all-or-nothing bytes
  on every failed open, perfect erasure, or protection against same-user malware/root.

Availability cost is material: an incomplete tail after a crash can be recoverable by
upstream truncation, yet G1 would REFUSE it. No automatic cleanup/retry is then available;
an explicit future recovery process would be needed, not deletion or a fresh login hidden
behind an error. Backups may preserve copies but do not prevent mutation of originals.

## 5. Branch/control coverage and prospective interception matrix

All changes in this table are proposals, NOT patches made in Batch A. Test IDs refer to
section7. No row is claimed compliant merely because the worker discards stderr or closes.

| ID / pinned branch | Existing behavior | Candidate G1 boundary; normal behavior retained | Unknown/risk; proposed tests |
| --- | --- | --- | --- |
| M1 `RawSqliteDb.cpp:28–46` [S4] | `SQLITE_CORRUPT` attempts main/sidecar unlink before returning error. | Remove corruption-destroy fallback; propagate failure and stop use. Normal valid reads/writes retained. | Audit callers using fatal `ensure` and all related raw errors; T2,T10. |
| M2 `SqliteDb.cpp:74–94` [S5] | Failed stat with creation allowed calls destroy(path), then opens read/write/create; not every stat error is proven absence. | Distinguish explicit NEW/pristine vs EXISTING and missing vs other stat errors; refuse orphan/partial/unsafe states, no sidecar cleanup. | Creation metadata crash boundary/races and SQLite's own WAL effects remain; T1,T3,T11. |
| M3 `TdDb.cpp:376–381,533–535,561–571` [S7] | Disabled/unauthorized SQLite cleanup; any first SQLite-init error destroys and retries. | Refuse inconsistent existing state/flag changes; single failure return without destroy/retry. Fresh initialization remains explicit. | Flags alone are not trusted provenance. Failure can follow earlier valid writes; T3,T4,T10. |
| M4 `SqliteDb.cpp:214–239,251–305` [S5] | Compatibility-mode retry; key-change helper may retry old key, create/destroy `.encrypted`, export/rename/rekey. | Existing-store key/format mismatch is terminal; no implicit old/plaintext/compatibility migration fallback under initial G1 scope. | Some legitimate legacy stores cease opening; don't mislabel every path a wrong master key. T2,T5. |
| M5 `Binlog.cpp:194–225` [S6] | Missing-main `.new` rename attempt, read/write/create, load, wrong-password return, possible encryption reindex. | Explicit new/existing intent; no ambiguous `.new` promotion or missing-main recreation; validate accepted key/format before committing maintenance. | Key mismatch is sometimes detected without truncation; do not claim universal mutation. T1,T2,T6. |
| M6 `Binlog.cpp:390–402,524–577` [S6] | Load processor/reader errors and tail mismatch can truncate; callbacks precede final size reconciliation. | Separate load validation/error propagation from accepted replay/mutation; return error instead of truncate/fatal-repair. | `do_event`/`do_add_event` are void/shared with normal run/reindex: not a one-line early return. Partial/encrypted events and destructor/flush effects require audit. T6,T7,T10. |
| M7 `Binlog.cpp:250–253,633–683` [S6] | Normal compaction creates/truncates `.new`, writes/syncs, unlinks old path and renames. | Retain ONLY if normal-maintenance permission explicitly adopted, after store acceptance; preserve locking/order. | Power-loss/rename failure and old/new ambiguity must not auto-promote next run. G2 would prohibit it. T7,T8. |
| M8 `TdDb.cpp:392–465` + initializers [S7,S8] | Disabled components drop tables; newer-version/missing-table/legacy paths can drop/recreate; version and binlog metadata change. | Validate expected schema/flags/version first; refuse unapproved rebuild/downgrade/migration. | Table-level reset survives a filesystem-unlink-only patch; not all normal index/schema work is recovery. T4,T9. |
| M9 `AuthManager.cpp:359–380,1433–1464` → `Td.cpp:775–780,592` → `Global.cpp:43–45` → `TdDb.cpp:293–353` [S9] | Restored destroy state or authorization loss can trigger auth-key destruction, `Td::destroy` and database `close_and_destroy`, without a public app destroy request. | Design suspend/stop/quarantine on revoked authorization that preserves required local stores while preventing reuse; prohibit automatic destructive shutdown under approved G1. | Do NOT merely suppress remote revocation/security cleanup and carry on authorized. Broader actor/lifecycle work; T12. |

M8 examples: `DialogDb.cpp:32–39`, `MessageDb.cpp:49–55` recreate newer-version tables;
`StoryDb.cpp:33–39` drops on missing constituent tables or newer version;
`files/FileDb.cpp:45–50` drops a legacy version; `MessageThreadDb.cpp:35–38` has a newer-
version rebuild. These demonstrate why refusing unsupported versions/partial schemas must
be a native decision before destructive helpers, not only a wrapper version label.

M9 is a source-reachable chain, not an observed account event or proof every auth loss
immediately deletes files: key-destruction completion/state and actor shutdown govern it.
It is nevertheless a mandatory boundary for a broad no-auto-delete claim.

## 6. Smallest credible Batch B proposal and cost

**No ready-to-apply tiny patch established.** If G1 is chosen, use a dedicated fail-closed
native build/policy installed BEFORE `TdDb::open`, not an invented ordinary string option.
`Td::init` initializes options/network after opened-database success (`Td.cpp:839–866`), so
adding a late OptionManager setter alone is not a proved startup interception design.
Whether to expose a public schema option or keep a fixed private-build policy remains a
separate design choice; a fixed build would avoid claiming an upstream-supported API.

B1: native store-open/recovery engineering, confined to pinned storage layers, schema
initializers and propagation through TdDb. Deliver an experimental paired native artifact
and T1–T11 disposable tests, not account readiness. Explicit NEW vs EXISTING state, status-
bearing load failures and no implicit migration are central. Prove ordinary create/write/
close/reopen and permitted compaction still work, not only refusal tests.

B2: authorization-loss/destructive-close lifecycle design and implementation with T12,
including persisted `auth=destroy`, actor ordering, key revocation and quarantined reopen.
B1 passing does NOT authorize skipping B2 or claim the whole engine is fail-closed. No live
account tests required/authorized to implement native unit/actor fault seams with PUBLIC data.

Cost/risk: high, multi-layer C++/actor work + test seams and future upstream maintenance;
not the small Java transport follow-up. Key migration/schema/version assumptions and M9
make file-count/time promises unjustified. There is a clear candidate boundary but no
proof it is acceptably bounded until the approved implementation design/test seams exist.
If the operator does not want to maintain this workstream, keep strict-policy activation
blocked or separately reconsider policy; do not bury that trade-off under frontend work.

Provenance: base revision plus reviewed patch/build identity and hashes must be explicit;
verify generated Java/JNI compatibility, licenses and bundle schema/runtime staging. A
forked native binary must not be advertised as an unmodified upstream build. No dependency
lock or source changes were performed here. No alternate crypto/terminal backend needed.

## 7. Disposable PUBLIC acceptance matrix for future B

These are REQUIRED FUTURE tests, not results of Batch A. Native store creation/opening,
corruption/fault injection and rebuilt bundles require separate approval. No account or
genuine credentials/providers; network denied where applicable.

| Test | Setup/operation | Required evidence under approved G1 |
| --- | --- | --- |
| T1 | Explicit pristine NEW vs EXISTING missing main/binlog, orphan sidecars, partial first initialization | NEW creates only owned intended files; EXISTING/partial refuses without reset/promotion/fallback; crash-state classification explicit. |
| T2 | Wrong/missing/corrupt master/SQLite key/encryption header and compatibility variants | Safe fixed failure, no blank/old-key/format fallback or private logs; original stores preserved to approved scope. |
| T3 | Disabled flags, missing auth with existing SQLite, failed stat including non-ENOENT, init error | No native destroy/retry/cleanup; meaningful content/tree/metadata comparisons. |
| T4 | Changed flags and absent/inconsistent constituent tables | No silent drop/recreate; initial valid creation distinguished from existing damage. |
| T5 | `.encrypted` remnants, plaintext/old-key store, rekey/export failure | Refuse unapproved migration; do not truncate/delete remnants or replace originals. |
| T6 | Truncated/malformed/oversized/encrypted/partial binlog records, processor errors, `.new` combinations | Fail before prohibited repair/operational replay; no tail truncation or candidate promotion; original evidence retained. |
| T7 | Healthy writes/flush/Close/reopen and permitted compaction, plus interrupted writes | Normal durability works; suspect interrupted state refuses explicitly rather than automatically repairing. Not all failed opens are assumed byte-atomic. |
| T8 | Forced open/write/sync/rename/disk/permission failures during normal maintenance | Truthful failure, no destructive recovery/retry; before/after preservation assertions reflect the agreed normal-write boundary. |
| T9 | Unsupported newer/legacy schema and approved-version normal schema | Refuse unsupported migration/rebuild; valid supported stores remain usable. |
| T10 | Mid-run corruption/DB disappearance, fatal/error propagation, callback/replay ordering | Stop operations, no recreate/repair/resend; deterministic PUBLIC native seams + subprocess containment, not all-corruption detection proof. |
| T11 | Second owner, lease lifetime, safe roots/paths/modes/sidecars, startup/Close failures | No shared owner/reset/lock breaking; exact store/mode checks, parent cleanup not fake success. |
| T12 | Inject authorization loss and restored destroy state with native actor/network seam | Revoked session cannot remain usable; no silent destructive close/relogin; retained-store/quarantine/exit behavior meets reviewed contract. |

Record file bytes/tree/owner/modes/inodes and relevant write metadata before/after; explicitly
separate lock/kernel read-atime effects and normal permitted writes. Keep failed fixtures;
never restore originals automatically and call the mutation prevented. Positive native tests
must accompany refusal tests. All native work remains opt-in; preserve default SDK-free build.

## 8. Decision to take after independent review

1. Keep the literal strict guarantee and stop actual activation: current authorized default.
2. Explicitly adopt/revise the DRAFT G1 contract, then separately approve scoped B1/B2 native
   engineering. This is the recommended route ONLY if normal maintenance is acceptable and
   its cost/availability trade-off is accepted; not permission already granted.
3. Explicitly reconsider the recovery policy to permit specified upstream behavior. That
   weakens the current guarantee and needs its own exact scope/risks/tests, not a blanket
   instruction to ignore corruption or a shortcut through all live gates.

Until a decision and later native evidence exist, no Client.create/real database readiness,
operator auth/private data, sends or subsequent roadmap batches. Batch A is not a fork or
permission to execute its test matrix. Source/docs validation and independent source/design
review are appropriate here; rerunning JVM/native suites would not validate this proposal
and no such new runtime results are claimed.

## 9. Evidence references

Pinned source links (external revision, not app commit). Local roots are
`native-build/tdlib-a94b83d67ab4c8397c6245e541b31a4131ea2f79/`.

- S1: [td_api.tl parameters](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/generate/scheme/td_api.tl#L11669-L11684).
- S2: [TdDb.h Parameters](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/TdDb.h#L55-L63).
- S3: [OptionManager dispatch/custom options](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/OptionManager.cpp#L1076-L1120); optimizer observer754–755, background option960–962 in same file.
- S4: [RawSqliteDb corruption/destroy](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/tddb/td/db/detail/RawSqliteDb.cpp#L28-L46); [sidecar enumeration](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/tddb/td/db/detail/RawSqliteDb.h#L30-L36).
- S5: [SqliteDb init](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/tddb/td/db/SqliteDb.cpp#L74-L98), compatibility/key paths214–305 in same file.
- S6: [Binlog init](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/tddb/td/db/binlog/Binlog.cpp#L185-L228), processor failure390–402, load508–591, compaction633–683 in same file; Binlog.h interface56–58.
- S7: [TdDb initialization](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/TdDb.cpp#L360-L465), open508–571 and close293–353 in same file.
- S8: [DialogDb](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/DialogDb.cpp#L28-L39), [MessageDb](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/MessageDb.cpp#L45-L55), [StoryDb](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/StoryDb.cpp#L30-L39), [FileDb](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/files/FileDb.cpp#L40-L55), [MessageThreadDb](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/MessageThreadDb.cpp#L26-L59).
- S9: [AuthManager auth loss](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/AuthManager.cpp#L1433-L1464), restored destroy359–380; [Td destroy/close](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/Td.cpp#L771-L810), global close592, init839–866; [Global close forwarding](https://github.com/tdlib/td/blob/a94b83d67ab4c8397c6245e541b31a4131ea2f79/td/telegram/Global.cpp#L43-L45).
- W1: [Official TDLib options](https://core.telegram.org/tdlib/options), complete fetched list; live documentation, not version-pinned.
- W2: [Official setTdlibParameters reference](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1set_tdlib_parameters.html), parameter/key semantics, not no-mutation guarantee.
- W3: [Upstream issue699](https://github.com/tdlib/td/issues/699),2019 persistence semantics; historical context, not a current recovery-mode contract.
- W4: [Upstream issue1815](https://github.com/tdlib/td/issues/1815),2022 linkage/init-error example; later linkage fix not removal of all destroy/retry paths.
