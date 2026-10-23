# G1 owned native lifecycle contract

Status: **ownership and operation roles selected; full physical-stop contract OPEN**.
This checkpoint follows the fenced [passive-guard increment](native-g1-passive-guards.md).
It does not implement a worker, JNI domain, receiver or stop API. The factory remains fenced;
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

These are proposed private implementation roles, **not selected JNI signatures or wire tags**.
They define the obligations a fenced implementation must preserve.

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
still require review before implementation.

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

### Parent death is an additional open safety obligation

A parent-held OS lock is released when that parent dies, even if its child is still alive.
The selected placement therefore needs a demonstrated child-containment and re-admission
solution: a new supervisor must not admit a profile while the old child can still use it.
A best-effort signal, PID check, deadline or process-death observation is not assumed to
close that race or prove clean flush. Do not add a second lock descriptor or change the
112/120-byte records to solve it without a separate decision. This remains an activation
blocker; parent ownership alone is not a complete exclusive-lifetime proof.

## Bounded source findings and implementation boundary

Manual reads of the published descriptor10 candidate show:

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
No compiler, parser campaign, engine or account was run for this checkpoint. The native
code, descriptor10 identity, record layout and existing evidence stages remain unchanged.

**Checkpoint result:** selected ownership and normal-close/control obligations are recorded.
The full stop/resource roster, parent-death safety and concrete JNI/synchronization contract
are not yet implementation-ready. A new explicitly bounded source-sequencing permission is
needed before lifecycle plumbing can proceed ahead of that closure. Such plumbing must not
expose positive stop, release the lease, launch a genuine worker or activate the factory.
The remaining [activation obligations](read-only-activation-contract.md) are unchanged.
