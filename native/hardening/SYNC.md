# PUBLIC ConcurrentBinlog caller-reservation sync check

This is an opt-in **actual low-level storage actor** fixture, not TdDb/Client, a composed
account engine, Java worker, genuine authorization or a coherent candidate SDK build.
Production `integrated_engine_accepted` remains false. The default Maven/Python paths
never invoke this runner. The exact cached compiler/image/libraries and host preservation/
container lifecycle controls are shared with the other approved G1 manual runners.

```sh
python3 scripts/native/compile-g1-coordinator.py \
  --source-stage .pi/g1-coordinator-draft-5mfu_ctf --public-storage-actor-fixtures
```

Both executables use the same PUBLIC fixture and patched low-level binlog/context objects.
The control compiles the archive-pinned **original ConcurrentBinlog.cpp**. The candidate
compiles the private caller-reserved-watermark correction. Neither executable may contain
TdDb, ClientManager, Td, AuthManager, Session or AuthDataShared symbols; both must contain
ConcurrentBinlog. Cached `tdactor`/`tdutils` support libraries are not rebuilt or installed.

Each disposable PUBLIC profile has a caller-owned `PUBLIC_LEASE`, not `profile.lock`.
The storage helper creates an encrypted header with the PUBLIC raw-A32 key. The helper's
synthetic binding ID is not a Client instance. The admission record remains **unsealed**;
no authorization/seal hook is called. Bare-binlog replay is component verification, not
permission for an application to resume an unsealed first login.

A real single-thread TD scheduler drives a real ConcurrentBinlog actor. The fixture:

1. Reserves an event sequence number but deliberately does not queue its PUBLIC empty event.
2. Requests force-sync and observes actor entry using its existing PUBLIC-source INFO log.
   The fixture-only sink is not a production log/privacy strategy.
3. Runs the scheduler across the native3ms deadline and an additional50ms window **after**
   observing actor entry. It records whether the promise completed without the reserved event.
4. Checks the physical file is still128bytes; queues the actual32byte PUBLIC event; requests
   final sync; requires both sync promises and actual native close to complete successfully.
5. Destroys the scheduler/actor owner, reopens with the PUBLIC raw key, and requires exactly
   one empty PUBLIC event and the160byte physical file.

Expected control: exact exit21 and `early=1`. This is a specific observed premature-sync
outcome, **not arbitrary nonzero exit, timeout, crash or compilation/setup failure**.
Expected candidate: exact exit0 and `early=0`. Both require empty stderr, exact markers,
physical outcomes, pending112byte admission record and successful close/replay. A control
that fails to exhibit premature sync does not validate the fixture. All failures/stages
are retained; no automatic retry or source/SDK fallback.

This targets one legal producer/actor reservation gap with controlled scheduling. It is
not exhaustive concurrency/fault coverage, crash durability, directory-fsync certification,
clean account-engine shutdown, Java lease release, private-view purge, or a stable exact
PMC/config authorization/sync/seal/publication snapshot. Those gates remain open. Parent
acceptance requires exact container termination/removal and independent original/frozen
hash checks, not merely `allPassed` from the container.
