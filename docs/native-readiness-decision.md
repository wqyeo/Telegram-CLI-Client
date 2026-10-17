# Actual Telegram-client readiness: recovery decision

**Milestone 1 decision package. Status: real activation remains BLOCKED.**
This document does not approve a policy change, TDLib fork/revision change, actual client,
account database, credentials, network authorization or sending. The implemented process
preview uses PUBLIC fixtures only; its close acknowledgement is not TDLib `Close`.

## Decision required before the next product milestone

The desired next product outcome is an actual TDLib adapter, then operator-local
launch → authenticate → chats/history/updates → close → resume. At the pinned dependency,
**a supported path satisfying the retained strict no-reset/no-delete policy has not been
established**. The reviewed branches contradict a blanket claim that a Java wrapper can
prevent native recovery changes. Do not enable `Client.create` simply because the typed
subprocess tests pass. Stop real activation at this boundary.

Recommended present disposition: keep strict policy/pins and the real-client gate closed.
If the strict guarantee is mandatory, the next separately approved engineering scope is
an upstream-supported fail-closed mode or a reviewed TDLib hardening feasibility project,
not more fixture UI work. If this cannot be supported, say the client is blocked rather
than quietly weakening the guarantee. No option below is selected by this memo.

## Source evidence and limits

External dependency revision: `a94b83d67ab4c8397c6245e541b31a4131ea2f79`
(as pinned in `native/tdlib.lock.json`; not an application commit).
The following branches were re-read in the local pinned source. Line references refer
to that revision and are source evidence, **not actual-client execution or corruption
experiment results**. The original broader investigation is retained locally; no native
sources or bundle were patched/rebuilt for this decision package.

| Path at pinned TDLib revision | Reachable behavior | Consequence |
| --- | --- | --- |
| `tddb/td/db/detail/RawSqliteDb.cpp:28–46`, `RawSqliteDb.h::with_db_path` | `SQLITE_CORRUPT` marks destroyed and calls destroy; unlink enumerates main/journal/WAL/SHM. | Returning an error to Java can happen after native deletion. |
| `td/telegram/TdDb.cpp:360–378` | Disabling optional SQLite/file DB still calls `SqliteDb::destroy` on its path. | Optional-DB flags are not a no-delete switch. |
| `td/telegram/TdDb.cpp:508–535,561–571` | Binlog initialization is unconditional; absent auth can destroy SQLite; a failed first SQLite init destroys and retries. | Removing one low-level corruption handler would miss higher-level reset/retry paths. |
| `tddb/td/db/binlog/Binlog.cpp:194–218` | May promote `.new` when main is absent, opens read/write/create, loads before reporting wrong password. | Mandatory auth/config/event log is not a passive read-only store. Wrong-key errors must not lead to blank-key fallback or reprovisioning. |
| `tddb/td/db/binlog/Binlog.cpp:394,530,571–576` | Load/processor errors and incomplete tails have truncation paths. | Binlog content can change during recovery before wrapper intervention. |
| `tddb/td/db/binlog/Binlog.cpp:633–683` | Normal reindex/compaction writes/truncates `.new`, syncs, unlinks old path, renames replacement. | Normal engine maintenance and destructive recovery both use filesystem mutation. A literal ban on every unlink/truncate also forbids ordinary maintenance; no exception is implicitly granted here. |

Other known gates remain: flag promotion and explicit precreated paths; imported-binlog
replay of persisted sends; log paths/hex snippets; actual file/default-ACL/chmod/temp/
download permissions; database-key retention; true `Close`/exit/restart/exclusion and
upgrade behavior. The tested child-only `umask(0077)` is useful but does not establish
those properties or prevent native recovery. A fresh app-owned profile avoids foreign
replayed operations, not future corruption or native writes.

## Concrete alternatives (separate operator decisions)

### A. Retain strict policy; no native hardening authorization

No dependency change, no native client/database execution; real Telegram activation stays
blocked. Offline transport can be accepted now, but cannot itself deliver usable login,
read, receive, send or resume. This is the current authorized state, not a workaround.
Do not keep adding demos to disguise the blocker.

### B. Commission strict fail-closed TDLib feasibility/hardening

Preserve the intent: suspect/wrong-key/corrupt/unsupported stores must fail without
reset/delete/repair/truncation or automatic reauthorization. A separately reviewed design
must explicitly classify ordinary transactional maintenance versus suspect-store recovery;
that classification itself needs approval before implementation. Investigate a supported
upstream control first. Otherwise a fork affects C++ recovery paths, Java/JNI pairing,
source/license/provenance hashes, bundle/runtime packaging and ongoing upstream merges.
This is a distinct native workstream, not a small Java guard or a promised one-line patch.

Minimum design/validation work before claiming feasibility:
- Inventory low-level SQLite errors, higher-level destroy/retry/key-change/upgrade flows,
  binlog tail/processor repair and `.new` promotion, not just one `unlink` call.
- Specify byte-preservation and sidecar rules **before** native open; returning an error
  after mutation is insufficient. Preserve original failure evidence; no automatic repair.
- Distinguish crash interruption/normal WAL and compaction from prohibited repair. Establish
  correctness, locks, startup/Close/restart and safe failure behavior without making live
  accounts experimental subjects. Consider a separately implemented storage integration
  only with an explicit architecture decision, not an assumed sandbox/interposition fix.
- Use owned disposable PUBLIC native fixtures: wrong/missing/corrupt keys, malformed/truncated
  binlog records, corrupt SQLite/sidecars, `.new` states, interrupted maintenance, failed
  sync/rename/open/permission/disk conditions, concurrent ownership, upgrades/downgrades.
  Compare full trees/bytes/metadata before/after refused opens. Verify normal persistence
  and session resume still work. Independently review patched reachability and runtime
  tests; no test suite can promise all corruption is detected before every possible write.
- Rebuild and verify the exact official Java/JNI pairing, licensing and provenance; native
  source changes require new pins/hashes and later package evidence, all separately approved.

Cost/risk: the broadest engineering and maintenance burden; outcome may still be infeasible
at an acceptable scope. No schedule or security guarantee is implied by this source review.

### C. Explicitly narrow the recovery policy

Authorize a precisely enumerated subset of upstream recovery (for example, selected cache
rebuild/tail repair), with understood data loss and unchanged wrong-key/no-auto-reauthorize
rules. This is a **material weakening**, not compliance with the current strict policy.
It requires a separate operator choice covering which paths/data may change, error/reporting,
backup/retention semantics and tests. It does not become safe simply because a profile is
fresh or logs are suppressed; do not activate accounts under an undefined exception.

## Rejected substitutes

- Backups/snapshots retain copies; they do not prevent original native mutation or prove a
  faithful safe restoration. No automatic snapshot rollback/reset/delete is authorized.
- Hash/preflight checks cannot rule out corruption or I/O failure after validation.
- A read-only mount prevents required normal engine persistence; exceptions needed to make
  it usable are not a proved fail-closed recovery design.
- Filesystem syscall blocking alone cannot distinguish repair from legitimate transactions,
  may turn failures fatal or lose buffered data, and is not an approved security sandbox.
- Catching errors, disabling optional databases, wrapper request allowlists, umask, process
  isolation, or waiting for `Close` do not remove native recovery branches.

## Gate to milestone 2 (not granted)

1. Explicitly select a feasible recovery approach/policy; review any changed guarantee.
2. Approve a narrow disposable actual-client/database readiness batch, with native logs
   suppressed before initialization and actual storage/mode/ACL/lifecycle evidence.
3. Only after that passes, separately approve operator-local credentials/auth/read-only
   account use; keep all private values and data outside agent evidence and memory.
4. Sending remains a later explicit intent/outcome-reconciliation decision.

No real client, database, genuine provider, private account data or send was used to produce
this document or the PUBLIC subprocess transport. No dependency, CI, backend, Windows,
public package/source/wiki publication or recovery-policy change is part of milestone 1.
