# G1 observed wake tickets — optional internal draft

This is part of the operator-approved scoped wake-ticket mechanism in
`.pi/g1-pending-wake-ticket-proposal.md`. It does **not** activate admission retries
or establish required-initializer completion. The original wake checkpoint retained
descriptor6 and the 112/120-byte admission wire. The later exact-identity-pair rule
advances the descriptor to7, not the wire sizes; see `IDENTITY.md`.
`integrated_engine_accepted=false` remains in force.

## Scope and ownership

`BinlogKeyValue::try_observed_snapshot_pair` adds an optional API to the existing
nonblocking, exclusive borrowed-view machinery. Successful acquisition contains
views and no ticket. Healthy BUSY contains no views and a one-use ticket for the
actual contended owner. The second-map failure explicitly releases the first view
before returning; activity on that first map is not the second map's wake source.
Invalid owner, lock, lifecycle or capability remains an error, never healthy BUSY.

`KvWakeState` has a non-secret release/activity epoch, not a revision, authority,
identity or readiness stamp. It serializes subscription against release using a
notification-bookkeeping mutex, never a second map authority lock. Registration
compares the epoch observed **before** the failed lock probe. Release before
registration signals immediately; release after registration signals the one-shot
channel. Every adapted read/write guard unlocks its native lock before notifying.
Nonmutating reads, visitors and no-op writes therefore need not change revision.
Epoch exhaustion fail-stops rather than wrapping; its pure boundary helper is
also used by the actual release implementation.

There is no arbitrary-thread actor send from a map guard. The pinned Linux
EventFd implementation supplies callback-free, thread-safe signalling. Unsupported
backends compile-refuse. EventFd initialization/release can fail-stop in the pinned
implementation; this draft does not invent recoverable I/O semantics for them.

A ticket/subscription retains its native map owner. Registration is consumed even
on error. Cancellation is idempotent; registration is released before the owner.
Any poll observer must unsubscribe **on its owning scheduler before** cancelling
or destroying the registration. Cancelling this observer is not a native stop,
lease-release authorization, storage rollback or private-data purge proof.

## Opt-in PUBLIC proof draft

`wake-ticket-public-fixture.h` is included only by the manual snapshot fixture;
the host freezes it only for `--public-kv-snapshot-fixtures`. The runner is not a
default JVM test. It instantiates the actual new pair/ticket/subscription API and
adds **21 named wake families** to the existing 30 snapshot/storage-actor families:

- Success without a ticket; invalid pair/lifecycle errors rather than BUSY.
- First-map contention; second-map contention without first-map self-wake.
- Actual snapshot release before registration; one-use ticket registration.
- Actual native nonmutating visitor release with unchanged revision.
- Read-lock and no-op write-lock notification **adapter** release.
- One-shot registration followed by a distinct next epoch.
- Idempotent cancellation; owner retention and release.
- Native map close/recheck refusal despite a signalled channel.
- Invalid epoch/owner refusal and the shared epoch-exhaustion boundary helper.
- 64 TEST-ONLY release/registration interleavings and 64 release/cancel interleavings.
- Actual native visitor-thread signalling without an actor Scheduler on that thread.
- Real EventFd/poll/actor handling for an already signalled subscription and for
  a receiver already waiting before the actual other-thread lock release.
- Scheduler-owned unsubscribe/cancel before a late native visitor unlock.

The separate `PublicVisitorHold` fixture thread uses a controlled nonmutating
native visitor to force contention. The subscribing/pumping caller holds no
borrowed view. No scheduler is pumped, subscription allocated, or actor created
while that caller owns a borrowed snapshot. All waits, scheduler slices, iteration
counts and process bounds are **test harness policy only**, not production retry,
fairness, completion, timeout or deadline policy.

The read/no-op adapter cases are leaf coverage, not deterministic interception
inside the bodies of ordinary native `get` or unchanged `set`. Guard adaptation
still requires source review. The fixture's tiny `PublicWakeReceiver` is a PUBLIC
prototype, **not** a production TdDb/Td admission coordinator. Generation/Close
filtering, Changed coalescing, single-flight attempts and the local initializer
completion graph remain unwired and unproved. No additional store authority is
inferred from a signal; the receiver reacquires fresh views after releasing the
poll registration.

## Evidence status and gates

`.pi/g1-wake-ticket-compile-verification.json` independently consumes `b889aa359`:
10 native objects, seven private Java classes, 11 checks, 542 protected and 437
frozen hashes, logs and exact container absence. It covers the **first** source
draft, not the later counter-helper extraction or new PUBLIC fixtures. The new
API was not instantiated/executed by that compile-only run.

The new 51-family PUBLIC component **passed** as task `b7b7b7768`, stage
`.pi/native-g1-snapshot-public-8__t_sic/`. Parent receipt
`.pi/g1-wake-ticket-public-verification.json` independently consumes exact
exit/stdout/empty stderr, binary/storage/API symbols, object/source hashes,
pending record, actual tree, compiler/link/patch logs, all **544 protected / 441
frozen hashes**, helper alias and nine pinned references, and exact stopped/removed
container absence. All four storage objects match `b889aa359`. The record remains
112 bytes and unsealed; main is 41,556 bytes. Independent parsing covers the header
prefix, not the entire encrypted stream; native reopen/replay is separately tested.
The native input hold was released only after this parent evidence/log review.

This accepts the current API/notification/PUBLIC receiver as a **low-level
component**, not production admission. At that checkpoint the counter-helper version
was compiled/instantiated by the PUBLIC target but had not yet had a fresh full ten-object
compilation after extraction. FOLLOW-UP: descriptor7 compilation **b941cbe4a** now covers
that exact current helper (`ba57ce4971b9681767a03de924dfc18ff18e0f3413200c9cbdfe2d694b280b58`),
ten objects and seven private Java classes /11 checks, independently consumed in
`.pi/g1-identity-mirror-compile-verification.json`. This closes the source-compilation gap,
not production scheduling/barrier execution. Historical b889aa359/b7b7b7768 receipts remain
unchanged; the 51-family runtime was not rerun for descriptor7. There is still no
TdDb/Td/Client/Global execution, account initialization, genuine provider, credentials,
network or SDK activation permission here.

The earlier `b484c5d24` 24-PASS-prefix runtime failure remains **UNDETERMINED**;
a subsequent success does not explain, supersede or erase it. No stable account
seal, whole-engine authority, B2 revocation, lease integration or daily-client
acceptance follows from these low-level checks.
