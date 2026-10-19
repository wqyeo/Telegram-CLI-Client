# G1 promise disposal and native-client Closed boundaries

**Bounded source findings only.** `integrated_engine_accepted=false`; the factory stays
fenced. This refines [Close/repair boundaries](native-g1-close-and-repair.md), not the
selected admission contracts or the sole-lease rule. No compiler, TDLib client, engine,
account, network session or shutdown sequence was executed.

## Evidence and limits

At pinned TDLib revision `a94b83d67ab4c8397c6245e541b31a4131ea2f79`, a fresh private packet
checked **38 complete definitions**, plus one explicitly unusable whole-function range.
Ten files comprise eight archive-exact files and the accepted/protected candidate copies
of `td/telegram/Client.cpp` and `example/java/td_jni.cpp`. An independent parent consumed all
39 ranges and their hashes. Overloads and both conditional ClientManager implementations
are retained separately, not conflated because their qualified names match.

Three additional Java files were read and hash-bound: the candidate official Java Client,
the application NativeSessionWorker and SessionSupervisor. The Java response-receiver block
is byte-identical to the archive version; this is a body comparison/manual source trace,
not a new whole-Java syntax or generated-API compatibility certificate. Local receipts and
captures remain private, not public runtime evidence.

`ConcurrentScheduler::finish` remains **whole-function uncertified**: its raw Windows-
conditional `SCOPE_EXIT` has a grammar gap. Its conditional join/cleanup statements can be
reported as source observations, not as an accepted whole-function call/order certificate.
No preprocessing was used to remove that branch. This additional excluded definition does
not resolve or replace the four FileManager exclusions; those four remain unchanged.

## Destroying a promise is not silent disposal

In `tdutils/td/utils/Promise.h`:

| Operation | Source behavior | What must not be inferred |
|---|---|---|
| `Promise::reset` / owned interface destruction | Destroys the owned promise interface | No guarantee of callback-free cancellation |
| Ready `LambdaPromise` destruction | Calls `do_error` with `Lost promise` | Not proof that the intended recipient observed a terminal error |
| `do_error`, callable accepts `Result<ValueT>` | Invokes the callable with an error Result | Not proof of target delivery, consumer effects or durable completion |
| `do_error`, callable does not accept that Result | Invokes the callable with `Auto()` | No universal error channel; a Unit-only cleanup callable must not become a successful service-work acknowledgement |
| `SafePromise` destruction | Settles a still-held promise with its stored fallback Result | The fallback is not universally failure; it is not execution evidence |
| `Promise::set_value/set_error/set_result` | Calls the interface and then resets its owner | The callback is not automatically queued or detached from this stack |

`LambdaPromise::set_value` checks Ready, invokes its callable, **then** writes Complete.
Its error path also writes Complete after the callable. Therefore that state flag is not
an already-installed guard against arbitrary callback re-entry/self-disposal. This identifies
an obligation for a new acknowledgement owner; it is **not a demonstrated production
re-entry bug**, nor permission to change the native promise implementation.

A proposed D3 contract must distinguish service success, service failure, supersession,
delivery loss, owner destruction and Close. Merely reaching a callback, destroying an object
or receiving Unit cannot supply that distinction for every native callable shape.

## Event payloads can settle promises without running the target method

`tdactor/td/actor/impl/Event.h` owns custom events through a virtual CustomEvent destructor.
`Event::clear` and its destructor delete the custom event. ClosureEvent owns its delayed
closure; LambdaEvent owns its callable. In `tdutils/td/utils/Closure.h`, delayed closures own
a tuple of decayed arguments and invoke the method through that tuple when run.

Consequently, destroying a queued owned payload can destroy its captured promises without
running the intended actor method. The promise destruction rules above still apply. That
can execute a completion/cleanup callable on the disposal stack, or attempt another dispatch;
it is not equivalent to successfully running the original work.

`ActorInfo::destroy_actor` conditionally deletes an owned actor, clears the actor pointer and
clears its mailbox. A borrowed actor (`Deleter::None`) is not deleted there. `Actor::clear`
moves out its ActorInfo ownership; `Scheduler::do_stop_actor` composes actor clearing,
destruction and scheduler removal. These are distinct from a child handle reset and from
later destruction of a borrowed manager's containing object.

Immediate dispatch also has a useful specific limit: the scheduler's eligibility predicate
requires a current, non-migrating target that is **not running and has an empty mailbox**.
Thus a send to the currently running actor does not recursively execute that same target
through this predicate. Other eligible targets and direct promise callables remain possible
synchronous boundaries; this is not a universal no-re-entry guarantee.

The earlier dispatch trace still applies: absent targets/closing schedulers can refuse
delivery, while local/cross-scheduler paths own queued work differently. This packet does
not close every cross-scheduler queue, migration, shutdown race or transitive payload owner.

## FileLoadManager and GC: the missing edge is now more specific

The existing accepted FileLoad source packet shows synchronous helper calls followed by
individual promise settlement: content/part reads, local-location checks and unlink. Its
header has no custom manager-wide hangup/drain implementation. Unlink ignores its status
before returning Unit, so that Unit remains unsuitable as deletion/purge proof.

FileManager creates FileLoadManager without the Td shared-parent reference used by the
other three file managers; its hangup body does not reset the load handle. A registered
borrowed FileManager actor can stop before the containing owned FileManager object is
reset. The remaining load handle must therefore be followed through member destruction,
actor-hangup processing, any executing helper and final scheduler disposal. No manager-wide
interrupt/join or guarantee of finished IO follows from resetting that handle.

Deferred GC adds another branch. The earlier destroy-on-scheduler helper moves containers
into a promise action, executing it on the current scheduler or using another worker.
Promise/event destruction can release such captured containers even when a scheduled target
method is not run. An action being submitted, invoked or discarded is not interchangeable
with every captured owner/descendant being disposed and every resource being stopped.
This is a sharper roster requirement, **not a leak diagnosis**.

## Native ClientManager strengthens the outward Closed boundary

Do not conflate these three observations in the multithreaded/EventFD-supported source
branch of candidate `td/telegram/Client.cpp`:

1. **Td produces authorizationStateClosed.** ClientManager consumes this update internally,
   marks/closes the client through `close_impl`, and suppresses that outward response.
2. **The Td callback object is destroyed.** `TdReceiver::create_callback` supplies a callback
   whose destructor enqueues `{client_id, 0, nullptr}`. A never-initialized client has a
   separate marker path in `close_impl`; its marker is not evidence that it ever owned Td.
3. **ClientManager consumes that marker.** It checks that the client was closed, erases its
   mapping, then constructs an outward authorizationStateClosed. If no mappings remain it
   also tries to clear the pool.

JNI `Client_nativeClientReceive` uses the singleton ClientManager receive path, not a direct
Td callback. Thus the outward Java Closed update in this branch is **stronger than Td's
initial logical Closed update**: it follows the callback-destruction marker and mapping
removal. It must not be described as merely forwarding the first Td notification.

### Conditional scheduler destruction is real machinery, not universal proof

The implementation pool holds weak references. Erasing the mapping can drop the last
shared reference to its MultiImpl, but another client may still share that implementation.
`pool.try_clear` checks outstanding references; it is not itself a command to join an
arbitrarily shared scheduler.

In the normal, non-ExitGuard-exited `MultiImpl` destructor, the source resets MultiTd,
requests scheduler finish, joins the scheduler thread and calls ConcurrentScheduler finish.
The latter's normal threaded branch contains worker-thread joins, scheduler clearing and
at-finish callbacks. These are useful concrete join edges to compose, not missing machinery
to dismiss. Its whole-function syntax/configuration certificate is still absent as noted
above. ExitGuard-exited branches detach threads or skip draining instead; they must not be
collapsed into the normal clean-shutdown case.

The source therefore supports **conditional last-owner join**, not “every client's Closed
always joins all shared threads,” and not “Closed has no destruction ordering at all.”
Neither statement would be accurate. The separate thread-unsupported/EventFD-unsupported
implementation also remains a distinct branch. Reading conditions in these files does not
certify the configuration of an actual Client translation unit or a coherent SDK runtime.

## Java callback and application-worker boundaries remain separate

The Java response receiver invokes the selected result/update handler before removing the
closed client's update/default-exception handlers and decrementing its client count. Its
receiver loop is continuous and started on a daemon thread; Closed does not join that Java
thread. A handler invocation, handler-map removal and zero Java client count are separate
observations from native scheduler/GC termination and application-reader completion.

The application's current NativeSessionWorker is explicitly account-free: it validates and
closes synthetic bootstrap material before JNI and executes only the synchronous text-entity
probe. It does not create the genuine guarded client. Its worker acknowledgement cannot
stand in for a genuine G1 engine Close/stop observation.

SessionSupervisor separately composes protocol frames with EOF, successful process
termination and reader completion; its read loop explicitly says EOF alone is insufficient.
That existing transport machinery does not supply the missing genuine-engine acknowledgement,
resource roster or generation-bound native-stop/lease handoff. Failure/forced termination
must not be relabeled a clean database flush or an authenticated reopen.

## Remaining decision boundary

Before selecting D3 or implementing the shared reader, close these named obligations:

- Resolve the four FileManager bodies under an explicitly approved approach. The
  [preprocessing proposal](native-g1-preprocessing-proposal.md) has not been executed.
- Trace each required promise owner through result, disposal, failed delivery, supersession
  and Close, including its callable shape and current/native versus value/session identity.
- Complete FileLoad/transfer/generation descendants, in-flight IO, GC and actor/queue disposal;
  compose the actual normal last-owner/client/worker shutdown path, not just a local counter.
- Bind the outward native event and any stronger stop observation to the same profile,
  retained directories, attempt and sole Java lease; account for sharing and uninitialized
  clients without inventing a stop API or treating a removed handle as stopped resources.
- Preserve repair/reload cycle freedom and explicitly separate service completion from
  cancellation/disposal and from durable seals. No finite-readiness guarantee, retry cap,
  timer or new acceptance domain is selected by this trace.

The five selected roles remain unimplemented, the other 120 integer contracts remain open,
and the original reserved-gap failure remains UNDETERMINED. No actual native stop, lease
release, clean flush, authenticated reopen or engine acceptance is claimed.
