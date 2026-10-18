# PUBLIC key-value snapshot primitives

Opt-in, Linux-first internal source work approved in
`.pi/g1-stable-publication-proposal.md`. Original native cache, SDK/JAR/JNI and
all prior evidence remain unchanged. Default JVM verification never runs this.

Private `BinlogKeyValue.h` tracks logical mutation revisions under its existing
write mutex. Same-value sets and empty erase/batch/prefix operations do not bump.
Replay mutations bump; reset/import/close/value-move and finish/rebinding allocate fresh
in-memory incarnations. Displaced writer owners release outside the map lock. Value move-out/back cannot recreate an old token at the same origin.
No filesystem identity, credential/key hashes or metadata fields are added.
The exhaustion helper refuses uint64 wrap; the map's check is fail-stop, not reset.

`try_snapshot` / `try_snapshot_pair` return an owned, scoped borrowed view. Empty
successful result means BUSY; invalid/uninitialized/closing state or lock failure is
an error. Views retain shared owners and unlock before releasing them, including
move assignment. Pair acquisition releases partial guards, never waits for the
second map. `find` distinguishes absent/empty data and does not copy private values;
visitation uses the map's const visitor under its correct EXCLUSIVE protection.

Caller contract: do not retain borrowed slices after guard release, invoke ordinary
KV methods, move the contained object through another alias, call external callbacks
or await actors/sync while holding a view. Lifecycle moves/imports require quiescent
callers; public Java cannot acquire these views. These are internal primitives, not
a same-process security boundary or independent proof of a live binlog/account.

`RwMutex::try_lock_write` distinguishes POSIX EBUSY from other errors. Existing
blocking APIs are unchanged. Non-POSIX snapshot requests explicitly refuse; no new
Windows/backend adoption or provisioning is implemented.

Manual PUBLIC runner:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/native/compile-g1-coordinator.py \
  --source-stage .pi/g1-coordinator-draft-5mfu_ctf --public-kv-snapshot-fixtures
```

The private stage must include archive-verified original hashes for the KV/RwMutex and
ConcurrentBinlog headers. The container compiles four storage/helper objects and a strict fixture
against fresh private headers, with cached supporting libraries only. Before execution,
symbol checks require real ConcurrentBinlog, snapshot and PUBLIC deferred actor code,
and refuse TdDb/Client/Td/AuthManager/Session/AuthDataShared/Global. Libc umask0077 is
first in the child. Caller-only PUBLIC_LEASE is not the production Java lease.

Current descriptor6 fixture targets 30 named families: actual native KV mutation/no-op/ABA/batch/prefix,
borrowed views, contention/partial release, deferred owner-actor inspection after guard
release, owner lifetime, zero-revision reset and value-move ABA, invalid lock, revision
helper exhaustion, actual native force-sync/close/reopen/replay, and import lifecycles.
Eight added families target nonrecursive borrowed getters, finish/rebind incarnation and
PUBLIC owner-release hook, deferred native sync-handoff, changed/contended NOT READY,
observed reservation gap, actual healthy compaction and cancellation-before-handoff.
INFO entry/compaction capture is fixture-only logging, not production privacy.
The uint64 boundary test exercises the helper used by the map, NOT an actual exhausted
map/process-abort injection. Exact ordered stdout/exit0/empty stderr, hashes, physical
PUBLIC trees and pending112byte record are required; any compile/link/error/timeout is
not success. No seal/authorization simulation is needed: metadata remains UNSEALED.

Descriptor6 NOW has guarded TdDb/Td SOURCE wiring: capture/validate both borrowed views,
release before force_sync_handoff, then compare/revalidate/seal/internal Native Ready on
an explicitly deferred binlog-actor turn. Outward results post to Td only after releasing
guards. BUSY/CHANGED is typed NOT READY without stopping-as-corruption or auto-rescheduling.
Descriptor6 source has passed10object/private Java compilation. Initial PUBLIC run FAILED
after24 checks; diagnostic-only revision then passed30. Both independently consumed and
preserved; the pass does NOT explain the original failure. No native production change.
Completion-driven fixture hardening passed all30 families and was independently consumed;
it replaces fixed positive50ms pumping with
completion predicates:1s TEST-ONLY bound,5ms scheduler slices, unchanged overall8s child
limit. Negative gap window requires an actual scheduler timer >=50ms AFTER observed writer
entry, not just host elapsed time. No assertion is removed and no sync/seal is retried.
This is not production retry/deadline policy and not proof of the earlier failure's cause.
No runtime account acceptance: PUBLIC tests never execute TdDb/Td.
Full semantic/initializer inventory, healthy-churn scheduling, post-commit B2 generations/
revocation/purge and actual composed lifecycle still need completion. No retry budget selected. Primitive
success is not stable account admission, account close, crash durability, byte atomicity,
all-corruption detection or perfect erasure. Factory remains `integrated_engine_accepted=false`.
Actual TdDb/Client execution, full coherent SDK builds/activation, genuine providers/account/
network, sending and publication still require separate approvals.
