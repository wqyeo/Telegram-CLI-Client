# G1 Close and reference-repair completion boundaries

**Bounded source findings, not a selected completion API or shutdown proof.**
`integrated_engine_accepted=false`. Read the [query lifecycle findings](native-g1-query-lifecycle.md)
and [source checkpoint](native-g1-checkpoint.md) first. No engine/account operation, native
production change, new policy, deadline, finite-completion guarantee or lease-release rule
was exercised or selected.

## Evidence boundary

Source revision `a94b83d67ab4c8397c6245e541b31a4131ea2f79`; paths are TDLib-relative.
The query packet reports 111 complete definitions and four explicit whole-function exclusions.
A separate addendum checked 16 complete definitions/ranges in five archive-exact files for
scheduler dispatch, deferred destruction and Config/FileReference manager disposal. Local
parent receipts remain private; this publication is not independent public runtime evidence.
The four raw-function exclusions remain blockers, not cured by the addendum.

## Dispatch is not uniformly deferred

`tdactor/td/actor/impl/Scheduler-decl.h` routes ordinary `send_closure` to
`send_closure_immediately`, and `send_event` to `send_immediately`. In `Scheduler.h`,
`send_immediately_impl` can run under EventGuard immediately, add to the local mailbox, or
send to another scheduler; it returns without delivery when the actor is absent or scheduler
is closing. Explicit later variants take the later-send path instead.

Consequently:

- A callback boundary is not automatically an asynchronous ownership boundary.
- `ActorOwn::reset` and `ActorShared::reset` dispatch hangup; they do not universally defer it,
  wait for all descendant resources, or supply a physical-stop acknowledgement.
- Re-entry must be considered where a callback is invoked before node removal.
- Event delivery/disposal and the referenced actor's lifetime must be checked separately.
  A returned send call cannot certify that the target consumed or completed the request.

The query document's callback wrappers should be read as dispatch edges, not guarantees that
all paths queue work. Scheduler conditions, not a naming convention, determine immediacy.

## Candidate Td Close ordering

The private candidate `Td.cpp` is separately pinned to the accepted source/compilation copy.
The selected source sequence, not an executed lifecycle, is:

1. `close_impl` stops the G1 context, clears its Ready publication flag and forces
   nondestructive Close for that context. These are logical state changes, not resource stop.
2. In the running path it marks the session closing, sets Global Close, sends AuthManager
   closing, saves update positions, clears request actors, requests database flush and sends
   a later request-reference decrement. The pre-parameter path is different and must not be
   modeled as if all managers had already been constructed.
3. Request-reference completion enters `clear`: stop query-creator checks and dispatch, clear result
   handlers/requests and release actor handles, including the registered FileManager actor.
4. `FileManager::hangup` resets download/upload/generate handles and drains its query containers
   through abort handlers before marking itself closed/stopping; `tear_down` releases its Td
   reference. The four excluded functions prevent whole terminal/error-path certification.
5. Td actor-reference phases eventually reset owned manager objects, including FileManager,
   then call `Global::close_all`. That method delegates to TdDb close and clears StateManager.
6. A later reference phase produces authorizationStateClosed; stop-count processing controls
   Td actor stop. No selected step is a worker/scheduler join or Java lease-release proof.

The counters are useful native ownership machinery, but not a complete G1 resource roster.
In particular, FileLoadManager is created without the Td shared parent passed to the other
three file managers. Its owned handle is not reset in FileManager's hangup body; remaining
member destruction must be composed with the final stop observation. This is an identified
missing proof edge, **not a demonstrated leak, early-close incident or corrupt reopen**.

## Close/lifetime matrix

| Close point | Source-supported behavior to account for | Still-required integration proof |
|---|---|---|
| Before managers exist | Td has a separate WaitParameters close path | No assumptions about child creation; close pending promises without constructing new resources |
| After query registration/before delivery | Sends can run immediately, queue, or be discarded for absent/closing actors | Ownership/disposal of arguments and promises on every delivery outcome |
| During active download/upload/generation | Manager hangup resets owned workers; token/shared-hangup and query-map paths track further completion | Actual child/network/FD termination, not local cancellation or map removal |
| During synchronous FileLoad work | Methods perform file IO/checks and fulfill their individual promises | Cannot infer interruption or a manager-wide drain from handle reset |
| Result already in flight | FileManager wrappers have closed guards; query-container generation and node-active matching differ | Duplicate/stale result handling without re-entering a removed query CHECK or acknowledging a newer attempt |
| Merge transfers ownership | Alias resolution and repair proxies can change the destination node/generation | One truthful terminal disposition for the accepted value; no borrowed lifetime/session-token inference |
| A newer option value supersedes work | Reload promises and native repair generations are not PMC revision/session seals | Bind required work and outward publication to the correct attempt/value; dispose of superseded work |
| Root actor stopped, owned object not yet destroyed | Td reference phases and object reset are separate | Include uncounted child/member lifetimes and deferred destruction |
| Destruction scheduled on GC scheduler | Captured containers are moved into a scheduled action | Observe required destruction/join boundary, not merely scheduling its action |

These are proof obligations, not selected behavior for unresolved cases. Close can revoke
publication eligibility before work ends; that does not transfer or erase resource ownership.

## Deferred GC and promises

FileManager and FileReferenceManager destructors use `destroy_on_scheduler` for large state.
The variadic helper moves containers into a promise closure. `destroy_on_scheduler_impl`
runs under an empty actor context; `run_on_scheduler` either creates a worker on another
scheduler or executes the action on the current one. Thus even destruction is conditional
on scheduler placement, not uniformly delayed or uniformly complete at the destructor call.

FileReferenceManager teardown releases its parent reference; pending query promises can live
in the subsequently destroyed nodes. Its `on_query_result` checks Global Close BEFORE
reducing active-query counts. A zero-active-count test therefore cannot stand in for Close
completion/disposal. ConfigManager hangup reduces its own reference count, drops its recoverer
and stops at zero; that is not a certificate that every consumer effect or promise callback
has completed. Exact promise/event destruction and client/worker join remain named edges.

## Repair/reload dependency graph

For an eligible request with an app-config file source, the conditional path is:

```text
file request requires a reference
  -> FileReferenceManager::send_query (native destination node/generation)
  -> ConfigManager::reload_app_config
  -> help_getAppConfig result
  -> process_app_config / option updates / file registration and merge
  -> complete the reload promises
  -> FileManager::on_file_reference_repaired
  -> FileReferenceManager::on_query_result
  -> repair promise / request continuation
```

The app-config update/registration links reuse the prior bounded producer/consumer source
trace; no whole Config/Option/Stickers or every FileSource family closure is claimed here.
`try_request_app_config` dispatches when the combined get/reload promise count becomes one.
`on_result` moves the current promise vectors out before processing the response. A modified
response updates cached config/PMC; a valid not-modified response uses the cached config.
Premium options are updated before settling a successful batch. This coalesces fetches; it
does not acknowledge file/message-child completion or durable seal sync. A later
caller/value is not thereby bound to an earlier snapshot or an application session epoch.

`on_file_reference_repaired` checks Close, validates active remote locations and can remove
an unsuccessful source before completing its promise. Native query generations/proxies
route repairs after merges; they are not G1 session/admission generations.

### Conditional wait cycle to avoid introducing

If a proposed reload acknowledgement waited for the requesting download/repair to finish,
while that repair still awaited the reload acknowledgement, the new contract would contain
a wait cycle. The source path establishes the dependency to check; **no such new
acknowledgement exists here, and no existing production deadlock has been demonstrated**.

Before choosing D3, separate and classify:

| Work category | Question that remains to be decided |
|---|---|
| Finite local value/application work | Which registration, membership and direct-owner effects are required before readiness? |
| Repair/transfer continuations | Required, continuing or excluded under which enforced predicate? Which dependencies await one another? |
| Remote read/mutation and persistence | Which effects may run in each phase and what do their replies actually acknowledge? |
| Terminal failure and Close | Which error refuses publication, who disposes of remaining work, and what proves resources stopped? |

A predeclared obligation roster and structured child-completion mechanism remain alternatives
to evaluate, not adopted signatures. No UI/SQLite shortcut, timeout, retry limit, fairness or
finite readiness guarantee is implied. Continuous healthy contention may remain NOT READY;
Close must remain available.

## Remaining blockers and next boundary

- Resolve the four raw whole-function capture gaps without weakening syntax refusal; any
  new preprocessing/toolchain action needs its own applicable scope, not a changed grammar
  or source masking passed off as the original.
- Close concrete promise/event disposal, re-entry and late/duplicate paths; distinguish
  query generations from value/admission/session identity.
- Complete required-child/resource roster, GC and native-client/worker/scheduler join;
  compose actual stop with worker ack, EOF, zero exit, reader completion and the sole lease.
- Join direct Messages/QuickReply/Welcome owners, newer values, input bounds, D1-D4 choices
  and the other Packet A/B families before W07 can release the combined implementation.

Five fixed-role contracts are still selected but unimplemented; 120 other integer contracts
and broader schemas remain open. The original reserved-gap failure remains UNDETERMINED.
No descriptor/wire/factory change, actual stop, clean reopen or engine acceptance follows
from these documents. Further runtime, genuine profile/network, send/reply and release work
remain separate gates.
