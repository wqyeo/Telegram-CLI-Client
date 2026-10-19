# G1 app-config repair promises and FileLoad/GC stop register

**Conditional source register, not an implemented completion API or actual-stop proof.**
`integrated_engine_accepted=false`; the factory remains fenced. Read the
[disposal/client findings](native-g1-disposal-and-client-close.md) and
[Close/repair boundaries](native-g1-close-and-repair.md) alongside this register.

Scope: an eligible download/upload reference repair whose selected source is AppConfig,
its config batch and repair continuations, plus concrete FileLoad/read-retry/GC owners.
Other FileSource arms and all transfer/generation descendants are not closed here. No
compiler, SDK, TDLib client, profile/network operation or shutdown sequence was executed.

## Evidence boundary

Pinned revision: `a94b83d67ab4c8397c6245e541b31a4131ea2f79`; paths below are TDLib-relative.
A bounded packet contains **21 complete definitions and one excluded whole-function range**
in 17 files: 16 archive-exact files and the accepted/protected private Td.cpp. The parent
also consumed **26 function ranges from prior accepted packets**, with current source
identities and receipts checked; those exclusive parents were not rerun. Counts describe
source definitions/ranges, not independent resources or proof of runtime completion.

The additional exclusion is `check_full_local_location` in
`td/telegram/files/FileLoaderUtils.cpp`, lines273–351: raw missing `>` at line299, in the
comparison area. It receives no whole-function call/order certificate. Its wrapper is
complete; `check_partial_local_location` is separately complete. This does not cure the
four FileManager gaps or the raw ConcurrentScheduler finish gap. No masking, alternate
grammar or preprocessing was used. Private receipts are not public engine evidence.

## Concrete promise owners

P-D through P-G below are analytical labels, **not proposed API names**. Dispatch edges can
run immediately, queue or fail delivery under the scheduler conditions already documented.

| Label / creation | Holder and handoff | What its callback actually means |
|---|---|---|
| **P-D: download repair waiter** — `FileManager::run_download` | A DownloadWaitFileReference query is created; a Result<Unit> lambda captures FileManager ActorId, native query ID and FileId. The context forwards the promise to FileReferenceManager. | Success becomes the `FILE_DOWNLOAD_RESTART_WITH_FILE_REFERENCE` error marker; failure forwards its error to `on_download_error`. Neither means download completion. |
| **P-U: upload repair waiter** — `FileManager::run_upload` | An UploadWaitFileReference query is created; its Result<Unit> lambda captures FileManager ActorId and native query ID. | The lambda ignores its Result and always dispatches `FILE_UPLOAD_RESTART_WITH_FILE_REFERENCE` to `on_upload_error`. Error/lost-promise notification can take the same continuation. It is not a successful-upload or successful-repair acknowledgement. |
| **P-Q: query waiter storage** — `FileReferenceManager::repair_file_reference` | Resolve to current main FileId, create/reuse Node.query, append P-D/P-U to `Query::promises`, then run the node. | These are the actual waiting caller promises. The query's active count tracks source attempts, not physical-resource stop or value publication. |
| **P-R: source reload result** — outer lambda in `FileReferenceManager::send_query` | Captures FileReferenceManager and FileManager ActorIds, native Destination and FileSourceId. In the AppConfig arm it moves into `ConfigManager::reload_app_config`. | On any Result it creates P-C and dispatches FileManager's repair validation. Fetch/batch completion has not yet settled P-Q. |
| **P-B: config batch storage** — `reload_app_config_queries_` | Holds P-R with other reload promises; get-JSON promises are held separately. `on_result` moves both vectors to local batch variables before processing. | Error fails the batch. Modified results process config/cache/PMC; valid not-modified uses cache. Premium-option work precedes batch settlement. This is not all file/message-child completion or a durable seal. |
| **P-C: repair-validation result** — inner lambda created by P-R | Moves into `FileManager::on_file_reference_repaired`; its Result<Unit> is converted to Status and dispatched to FileReferenceManager's `on_query_result`. | Confirms the result of this validation step, not a transfer, persistent flush or application-generation seal. |
| **P-G: GC destruction action** — `Scheduler::destroy_on_scheduler` | A Unit-only lambda captures moved containers/tuple. It is invoked on the current scheduler or held by a RunOnSchedulerWorker. | Its body is deliberately empty; destruction releases captured owners. Invocation or lost-promise callback is not evidence that service work ran or that every destruction-triggered continuation has ended. |

### The bridge is concrete, but carries native identities

Candidate `Td::init_file_manager` owns FileManager in a unique_ptr and separately registers
its actor. The local FileManagerContext forwards `repair_file_reference` by dispatching to
FileReferenceManager. It does not add a G1 value revision, admission attempt or Java worker
generation to the native promise.

P-D/P-U capture a transfer-query container ID; P-R/P-C capture a FileReferenceManager
Destination containing node ID and query generation. These are different identities. A
main FileId may change through alias/merge handling; none of these fields independently
identifies the accepted PMC value or current G1 session/admission attempt.

## Dispositions: follow the holder, not merely the callback

### Ordinary result and native source fallback

`run_node` waits while a source attempt is active. With no active attempt it can start another
source, fail waiting promises when sources are exhausted/recent repair rules apply, or clear
a query with no waiting promises. Those native source-selection/time rules are observations,
not selected G1 limits, retry caps or readiness policy.

P-R passes through FileManager validation. A nominally successful reload can become an error
if active upload/download remote locations are missing. Certain non-429 client-side errors
remove the source before the promise is settled. In `on_query_result`, a matching success
settles P-Q; failure can continue native source selection. Merge combines waiter vectors and
uses proxy destinations to route remaining results.

Even after P-Q success, P-D requests a download restart and P-U requests an upload restart.
The four excluded FileManager terminal/error bodies prevent certification of all subsequent
restart/error branches. **This register does not conclude that either transfer finished,
failed terminally or was resent. No runtime incident is diagnosed.**

### Close before or during the config/repair path

- `reload_app_config` checks Close before storing its incoming promise. A bot shortcut can
  return Unit without fetching config; Unit alone therefore does not prove a network fetch.
- P-B already stored in a ConfigManager member remains an owned promise until explicit
  settlement or member destruction. ConfigManager's hangup/reference count is not a count
  of every pending reload promise or all query resources.
- FileManager's validation checks Close and can fail P-C before normal validation/mutation.
  The checked Status macro explicitly returns through `promise.set_error` on error.
- FileReferenceManager's `on_query_result` checks Close **before** decreasing active queries
  or settling P-Q. Receiving its dispatch during Close does not drain that waiter vector.
- Teardown releases FileReferenceManager's parent; later object destruction moves nodes and
  file sources to GC. P-Q can therefore survive that teardown notification in the captured
  node/query containers.

### Lost delivery, owner destruction and fallback callbacks

P-D, P-U, P-R and P-C use Result-shaped lambdas. If their Ready LambdaPromise is destroyed,
the existing lost-promise error path can invoke that lambda synchronously:

| Destroyed holder | Consequence to account for |
|---|---|
| ConfigManager's stored P-R / a queued reload payload | P-R can attempt creation/dispatch of P-C even though the intended reload method/batch did not complete |
| Queued FileManager validation payload holding P-C | P-C can attempt a result dispatch without validation having run |
| Node/query/GC state holding P-D | Error continuation toward `on_download_error`, not successful repair |
| Node/query/GC state holding P-U | The same upload restart marker despite the Result being an error |
| GC worker/action holding P-G | Unit-only error fallback and captured-owner disposal; not service success |

Attempted dispatch is not observed delivery. Target absence, scheduler closing and native
query/generation matching still matter. A stale or proxy result is not an acknowledgement of
a newer waiter/value. These source paths do not select a universal duplicate-result policy.

### Batch coalescing does not bind a value/session attempt

The get/reload vectors coalesce the native fetch. Their move-out, and the batch helpers'
move-before-callback behavior, separate the current batch storage from later callers; they
do not attach a PMC revision or G1 generation. Processing and callbacks can cause further
work, and another batch/value must retain its own ownership. No waiter-count or callback
success substitutes for the settled snapshot/stamp/final same-actor seal requirements.

A new reload acknowledgement must not wait for the very repair whose continuation waits
for that acknowledgement. The dependency remains a design obligation, not an existing
production deadlock diagnosis or an adopted wait-all API.

## FileLoad, retry and GC stop roster

| Owner / work | Concrete lifetime/effect edge | What remains required for actual stop |
|---|---|---|
| Borrowed FileManager actor versus owned object | Td separately registers the unique_ptr-owned object. Actor routing stop and later object reset differ. | Compose both with member/descendant disposal; neither local counter nor actor-handle reset alone suffices. |
| `file_load_manager_` | Owned ActorOwn created on the selected slow-net scheduler, without the Td shared parent passed to the other three file managers. Hangup does not reset it; member destruction must dispose the handle. | Observe actor disposition and executing work, not merely the enclosing destructor body. |
| Content/part-read payload and FileLoad handler | Before delivery the payload owns the promise; during the call the handler owns it. Helpers run synchronously before individual result settlement. | Queue disposition, in-flight helper completion and any callback-created continuation. No interrupt guarantee. |
| Local read wrapper/FD | `read_file_impl` opens a local FileFd, reads, explicitly calls close on its normal result path, then returns content. `FileFd::close` resets its implementation. | This establishes a concrete normal-path wrapper ordering, not universal syscall-close, error-path or SDK resource-stop proof. |
| Local-location checks | Complete wrapper delegates full/partial checks. The partial helper's source is complete; the full helper is explicitly excluded. | Full-helper certificate and transitive blocking/resource/effect closure remain open. Native size/path rules are not newly selected admission policy. |
| Read-part result lambda | Owns the original caller promise and retry arguments. A qualifying partial-read error creates/releases a SleepActor rather than settling the caller. | Track that independent child; FileLoad method return is not end of this read request's work. |
| Released SleepActor | Owns a Unit promise; startup arms its timeout, timeout settles the promise and stops. ActorTraits disables its context requirement; no Td shared parent/retained ActorOwn is supplied at this call site. | Include it in shutdown disposal; FileManager destruction does not directly cancel a retained handle here. |
| Delete/unlink path | FileManager can drop local state/flush before dispatching unlink; empty/no-node paths can return Unit without unlink. FileLoad ignores unlink status before Unit. | Not physical deletion, secure erasure or durable purge evidence. B2 cannot adopt this acknowledgement. |
| GC containers/action/worker | FileManager and FileReferenceManager move containers into destruction actions; execution and captured-state destruction are separate observations. | Pending promise callbacks and any further dispatch/child attempts must be disposed, then composed with normal scheduler/client/worker stop. |

### A concrete disposal-created continuation: partial-read retry

`FileManager::read_file_part` has a Result<string> callback that, on a qualifying error,
attempts to create `RepeatReadFilePartActor`. Its Unit-only timer callback dispatches another
read with decremented `left_tries` and the original promise. Consequently:

- Losing the read-result promise can enter the same error/retry branch without the original
  FileLoad method running.
- Destroying the pending SleepActor promise can invoke its Unit-only callback without the
  timer expiring, attempting that read dispatch.
- The re-entered read method checks Close before IO, if it is delivered. Callback/child
  creation attempts, payload disposal and that check remain separate events.
- The existing native delay/left-tries branch is not a chosen G1 retry policy or a proof of
  finite completion for every possible caller argument. Actual actor creation/delivery was
  not exercised.

By contrast, `get_content` lacks that method-entry Close macro in the selected body; a
uniform guard cannot be inferred merely because the part-read method has one. Broader
routing/effect gating and supported-state exclusions still need explicit integration.

### Stop request versus handler completion

The base Actor hangup calls stop. Actor stop delegates to Scheduler; the selected scheduler
method sets the current event's Stop flag. EventGuard destruction later processes that flag
through `do_stop_actor`. This supplies a concrete handler-boundary stop edge, not an interrupt
of an already executing synchronous helper or a joined manager-wide drain.

The prior native-client trace supplies conditional last-owner scheduler joins. Sharing,
ExitGuard-exited paths, raw finish certification, Java receiver/reader lifetime and a genuine
profile/generation-bound worker stop acknowledgement remain separate obligations. No outward
Closed event, disposed promise or zero active-query count alone releases the sole lease.

## Next decision boundary

The [FileManager host-input preflight](native-g1-preprocessing-proposal.md) is prepared but
actual preprocessing still requires explicit execution approval. Four terminal/error gaps
remain; the additional full-location and scheduler-finish gaps are separately scoped, not
silently added to that proposed invocation.

Before selecting D3, classify which rows are required finite value/application work, native
repair/transfer continuations, continuing service work or enforced exclusions. Bind required
work to the correct value/attempt and give disposal/Close their own dispositions. Complete
the remaining IO/network/transfer/generation descendants and actual-stop observations.

No D1-D4 policy, new API, timeout, descriptor/wire/factory change or readiness publication
was selected. The five selected roles remain unimplemented; the other 120 integer contracts
and broader families remain open. The original reserved-gap failure is UNDETERMINED.
No actual stop, clean flush, lease release, authenticated reopen or engine acceptance follows.
