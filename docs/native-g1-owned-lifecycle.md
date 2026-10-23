# G1 owned native lifecycle contract

Status: **fenced domain/JNI/receiver source compiled; full physical-stop contract OPEN**.
The contract checkpoint follows the [passive-guard increment](native-g1-passive-guards.md).
A later explicit sequencing exception permits the source implementation below before full
stop/parent-death closure. No genuine worker launcher or positive-stop API is implemented.
The factory remains fenced;
`integrated_engine_accepted=false`. E1, E2, F, sends and releases remain separate gates.

## Selected ownership

For the future genuine G1 session:

- The supervising Java parent owns the **sole original profile lease** through the complete
  shutdown composition. The child never opens `profile.lock`; no duplicated lock descriptor,
  replacement lease, reflection or parsed `fileKey` is introduced.
- The G1 worker owns one native ClientManager domain, one managed client and one explicitly
  owned receiver. Context creation/binding, send and receive belong to that domain, never
  silently to singleton client IDs or the legacy static receiver.
- The receiver is the only receive owner. It drains and normally disposes its native domain;
  the controller initiates shutdown and joins the receiver. No callback joins its own thread.
- Preserve the current PUBLIC `NativeSessionWorker` probe and account-free synchronous
  `Client.execute` path. The parent-held lease is a future genuine-worker contract, not a
  claim that the existing probe already implements it.

The parent supplies the current generation and lease-attested profile identity over private,
validated IPC. This does not delegate lock ownership to the child or make a path/client ID
an authority token. The complete attestation/IPC integration remains unimplemented.

## Operation roles and ordering

These operation roles govern the private source candidate below. Its JNI signatures are
compiled source interfaces, **not accepted runtime APIs or application wire tags**.

| Role | Preconditions and required behavior |
|---|---|
| Prepare/bind | Validate the integration fence before effects; consume the matching one-use context once; bind the domain to its immutable generation/profile identity and sole client. Refuse mismatched, consumed or foreign handles without singleton fallback. |
| Submit | Validate domain/generation and lifecycle admission before decoding/dispatching work. Preserve the typed application allowlist; an internal SDK transport is not an arbitrary-function application gateway. |
| Receive | One owner, ordered processing and generation/client validation. Keep control wake-ups distinct from TDLib updates, request success and authorization state. Do not reserve an ordinary request ID or fabricate an update to signal control. |
| Request normal Close | Atomically stop admission of ordinary work and account for submissions already admitted. Request non-destructive native Close; do not logout, destroy keys, delete the store or write terminal refusal intent. Duplicate matching Close may coalesce, but is not completion. |
| Wake/control | Explicitly wake the owned receive path without selecting a new polling interval, timer, retry cap or completion deadline. A wake only requests inspection of control state. |
| Drain/dispose | Receiver processes the native close/retirement path and disposes the manager without a concurrent receive or destructor. Do not run final destruction while holding a registry/operation lock needed by another admitted call or finalizer. |
| Join/observe | Controller joins the receiver from outside it and records only the observations actually obtained. Domain retirement, disposal and join do not mint a physical-stop or lease-release capability. |

Close must define the linearization with in-flight submissions and retain the domain while
admitted JNI calls use it. Removing a registry entry or returning from a Java close method
cannot free a still-used manager. Handle reuse, cross-generation delivery and fallback to
legacy global handler maps are forbidden. Concrete handle/API and synchronization design
require actual-phase enforcement evidence before activation.

### Normal Close is not refusal Close

In the current candidate, `Td::stop_g1()` revokes context authority and, when running, stops
the dispatcher and writes PMC `auth=destroy` before `close_impl(false)`. That is an existing
terminal refusal path, not the normal Close operation for authenticated same-profile reopen.
A normal owned-domain close must reach the non-destructive native close path without that
terminal write. Do not weaken the existing refusal behavior to make normal shutdown work.
Context revocation alone is not evidence that the close/save or physical-stop work finished.

## Observation contract: no positive-stop shortcut

Keep these facts separate: Close requested; outward Closed received; native client mapping
retired; native manager disposed; receiver exited/joined; **all profile resources stopped**.
The earlier observations cannot be relabeled as the last one. No positive-stop JNI result,
boolean, counter, observer API or serialized proof is selected by this checkpoint.

| Resource | Required positive observation still to establish |
|---|---|
| Td and request actors | Current handler finished, native close/save completed, actor/object disposal and descendant obligations composed. |
| FileManager / FileLoad / FDs | Reachable helper ownership, in-flight I/O disposition and slow-net/other actor lifetimes closed; ordinary-path FD closure alone is insufficient. |
| Released retries / SleepActor | Lost delivery, cancellation and destruction-created continuations cannot leave unaccounted work; a timer flag is not completion. |
| Repair / transfer / generation / GC | Waiters and containers disposed through errors, Close and destruction, including callbacks that request another operation. |
| Native implementation / schedulers | Applicable last-owner normal joins plus scheduler clearing and finish callbacks completed; no ExitGuard detach/skip accepted as clean stop. |
| Receiver / JNI operations | No concurrent receive/disposal, outstanding native operation use or unjoined receiver; domain observations bound to the current generation/profile. |
| Parent / transport / lease | A genuine positive resource observation composed with acknowledgement, EOF, zero worker exit and completed readers before the parent permits lease release. |

`ProfileStore.NativeClaim.confirmNativeStopped()` is a trusted mutation of `nativeLive`, not
an observer. This checkpoint adds no caller. Abandonment acknowledgements, callback/Unit
settlement, handle removal, kill, timeout or process death do not supply the missing proof.
Errors and unknown observations must not become successful Close or permitted reopen.

### Parent death: selected fail-closed ownership policy

A parent-held OS lock is released when that parent dies, even if its child is still alive.
The operator selected retaining that **sole parent-owned lease** and designing a separate
**durable active-generation claim**. An existing, partial, malformed or uncertain claim
must refuse admission without deletion, expiry, PID-based recovery, adoption or repair.
A crash can consequently strand the profile indefinitely, even if its processes later die.
That availability cost is explicitly accepted; automatic recovery is not part of this policy.
The child still never opens `profile.lock`; no second or replacement lease is permitted.

This is a **selected design policy, not an implemented or demonstrated ownership barrier**.
The existing source does not enforce it:

- `ProfileStore.Lease.nativeLive` and the same-JVM held-lock set are in-memory facts.
  A new JVM can acquire the OS lock after parent death; acquiring it is not native admission.
- `StorageContext` binds a generation in memory and assumes the caller retains its lease.
  It does not persist that generation as active ownership.
- The 112/120-byte admission record records configuration and a fixed `G1SEALED` tag.
  A sealed record means neither inactive ownership nor a cleanly closed previous session.
  Its flags and tag must not be repurposed as an ownership bit.

#### Candidate publication and cleanup ordering

The following protocol is a bounded implementation proposal. The concrete artifact,
encoding, durability primitive and IPC roles still require specification before code.
It does not select a new wire tag, stop-observer API or proof capability.

1. The parent acquires and retains the original lease, validates the trusted profile
   identity, and refuses any existing ownership claim **before launching another worker**.
   Refusal must not inspect or normalize a conflicting claim into permission to proceed.
2. While holding that lease, the parent exclusively creates the claim, writes its complete
   generation/profile/policy binding and synchronizes its file. No overwrite or replacement
   of an older claim is allowed. Any failed or uncertain publication stops startup and
   retains the artifact; it does not grant a second attempt.
3. The containing-directory publication must also be durably synchronized **before native
   profile/context creation, database access or engine activation is permitted**. File
   synchronization alone is insufficient. One candidate is an ownership-only worker
   preparation phase, launched only after step 2: it validates the matching claim and
   attested directory, synchronizes that directory and acknowledges the binding. It must
   not create a StorageContext, native namespace, files directory or TDLib client yet.
   This avoids assuming a new parent-side SDK load or an undocumented directory-force
   guarantee. The preparation primitive and enforcement are not implemented or accepted.
4. Only after matching durable-publication confirmation may the parent grant the worker
   one generation-bound permission to begin native work. Private IPC EOF, a stale message
   or an unmatched generation never supplies that grant. The worker must enforce this
   gate at the earliest native entry, not only at an application command handler.
5. A later supervisor which acquires the OS lock still refuses any surviving claim. It
   must not signal an old PID, wait for a deadline, decide the claim is stale or clear it.
   This prevents lock reacquisition alone from authorizing an overlapping generation.
6. In the same live run, cleanup requires the still-valid original lease, the exact owned
   claim and matching generation/profile, the genuine positive resource-stop observation,
   and acknowledgement, EOF, zero worker exit and completed readers. Only then may the
   parent remove its own claim and durably synchronize the containing directory before
   reporting clean completion and releasing the lease. An unlink result alone is not a
   durable-cleanup result. Any unknown observation must not become successful Close.

Cleanup cannot precede worker exit: otherwise parent death could expose an absent claim
while the old worker can still use the profile. Claim cleanup does not upgrade an unsealed
NEW store into valid EXISTING storage or bypass any native admission/schema check. A proven
never-launched cancellation branch, if desired, needs a separate exact contract; exceptions
from process creation, promise disposal or context abandonment do not establish that case.

| Failure point | Required disposition in the proposed protocol |
|---|---|
| Before claim creation and before any worker launch | No native work has been permitted; ordinary admission rules still apply. |
| During publication, before launch or before a grant | Retain any artifact and refuse a later claim-bearing profile; no grant is inferred. |
| After durable publication, including parent death before or after the grant | Claim remains an admission barrier; the old worker may still exist. |
| During Close, disposal or transport shutdown without the full observation composition | Do not remove the claim or report clean completion. |
| After full observations, before/during claim removal | Old worker has already exited; cleanup failure is not reported as clean success. Any surviving/reappearing claim refuses future admission. |
| After durable cleanup but before lease release | Native work is already stopped and the worker has exited; the lease still serializes normal contenders. |

A machine crash before a confirmed grant cannot justify assuming native work occurred or
completed. Durability and restart behavior need actual filesystem/phase evidence; this
ordering argument is not a power-loss, rollback or universal atomicity guarantee.

#### Implementation gates still open

- Specify one fixed claim location outside the native slot's strict component inventory,
  its bounded encoding, exact generation/profile/policy binding and physical file checks.
  Do not put secrets, key hashes, account data, PID liveness or expiry into that contract.
- Specify and validate the supported directory synchronization and private preparation/
  grant protocol. No silent fallback to an unsupported force operation or eager native open.
- Enforce the barrier in all applicable admission/creation paths. An older binary which
  ignores the claim is not made safe by the new protocol; compatible admission identity
  and rejection of older descriptors must be explicit, without automatic adoption.
- Establish the resource-stop roster above and its observer before implementing cleanup.
  `confirmNativeStopped()` and domain-disposal acknowledgements remain insufficient.

The current descriptor10, its 112/120-byte records, default SDK and factory fence are
unchanged. The exact ownership format and publication mechanism have not been selected or
implemented. No parent-death, power-loss or native lifecycle test was run for this design.
The policy closes a recovery trade-off, **not** the activation safety obligation.

## Bounded source findings and implementation boundary

Manual reads of the preceding passive-guard descriptor10 candidate showed:

- `ClientManager::Impl` owns its pool; receive suppresses raw Closed, retires the mapping
  after callback disposal and can erase the last `MultiImpl` owner inside receive.
- `MultiImpl` normally joins its scheduler thread, but ExitGuard detaches it.
  `ConcurrentScheduler::finish` joins workers on its normal Linux path, then clears
  schedulers and invokes finish callbacks. Joins alone exclude neither later destruction
  work nor resources outside that ownership graph.
- JNI still routes context-aware creation/send/receive to the singleton. Java starts a
  static daemon receiver without retaining a join handle. The current probe worker holds
  its own lease and never creates a genuine TDLib session.

These are bounded source observations, not a complete transitive resource/call certificate.
No compiler, parser campaign, engine or account was run for the original contract checkpoint.
The subsequent source compilation below does not upgrade these observations into stop proof.
The descriptor10 admission identity and record layout remain unchanged; prior source and
consumed evidence stages are preserved.

## Explicitly permitted fenced source increment

The operator subsequently permitted joined domain/JNI/receiver source before the full
stop/parent-death contracts close, with one bounded component compilation. This changes
sequencing only. The candidate is carried by `native/hardening/coordinator-g1.patch`:

- Each owned domain has its own ClientManager and sole client. Its registry handle is not
  a pointer, FD or singleton client ID. Generation lookup precedes submission/receive;
  context consumption retains the existing one-use profile binding. Future worker
  orchestration must still enforce its single-domain topology and typed operation allowlist.
- `createOwnedDomain`, `ownedSend`, `ownedClose`, `ownedReceive` and `ownedDispose` are
  registered private JNI methods. The legacy context-aware factory now refuses rather
  than entering the singleton/static-daemon path. Non-G1 creation/execute are unchanged.
- Submission and Close serialize through the domain mutex. Normal Close allocates from
  the same request sequence and submits real TDLib Close, not a synthetic success or
  reserved request ID. Ordinary submission leaves sequence space for Close.
- The controller does not stop context prematurely: that could make a previously queued
  healthy seal callback take terminal `stop_g1` before the actor handles normal Close.
  Native `close_impl(false)` performs its normal context revocation instead.
- A queue control wake unblocks event-driven owned receive; the wake has no API object.
  A dormant never-submitted domain retires without activating a Td client. Only the
  receiver normally disposes its manager, outside registry/domain locks. A separately
  checked never-started construction-failure branch may dispose before receiver ownership.
- Java `OwnedSession` retains a non-daemon receiver, has per-session event delivery rather
  than legacy global maps, guards self-join and exposes `requestClose`/`awaitDisposal`.
  `OPEN` is a transport lifecycle label, **not** authorization or reader readiness.
  `DOMAIN_DISPOSED_UNVERIFIED` and `FAILED_UNVERIFIED` never permit lease release.
  The future adapter still owes pending-work/error disposition and ordered correlation;
  events can race the return from send. This is not a genuine application adapter.

One cached rootless component run compiled **14 native objects plus the private Java
wrapper (10 classes; 15 checks)**. Independent existing-output consumption matched
26 archive-original identities, reconstructed the exact coordinator patch/candidate,
checked the four changed compiled source files, all object/class hashes and 556 protected /
442 frozen identities. The container positively exited and was removed; the prior candidate
was unchanged. No component retry or PUBLIC fixture rerun occurred.

The four-path LSP probe was not clean: 42 missing-include cascades on two paths and two
inconclusive checks. No suppression/config repair was used. Compilation proves only its
conditional source scope: **no SDK link, JNI registration, manager/Close/wake/thread phase,
physical stop, parent-death behavior, engine or account was executed**. The PUBLIC probe,
lease implementation and default SDK were not replaced. Descriptor10 is not mechanically
bumped for an unaccepted source-only lifetime interface.

**Checkpoint result:** owned lifecycle source plumbing is implemented and compiled under
the fence. The full stop/resource roster, parent-death safety, actual-phase lifecycle and
genuine worker/lease orchestration remain open. No positive-stop API or
`confirmNativeStopped()` caller was added. The remaining
[activation obligations](read-only-activation-contract.md) are unchanged.
