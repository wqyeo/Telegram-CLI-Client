# G1 file-query lifecycle — bounded source findings

**Source investigation, not implementation or engine acceptance.**
`integrated_engine_accepted=false`; the guarded factory remains fenced. No new readiness
roster, domain rule, completion API, retry policy or resource-stop signal is selected here.
See the [G1 source checkpoint](native-g1-checkpoint.md) for the unchanged broader boundary.

## Provenance and limits

TDLib source revision: `a94b83d67ab4c8397c6245e541b31a4131ea2f79`.
Paths below are relative to that source tree, not this application's root.

The local bounded packet contains 111 syntax-complete function definitions and 2,915
independently checked function/call/argument/fragment or excluded-function ranges across
19 files: 18 archive-exact files and the previously accepted private candidate `Td.cpp`.
The latter matches its accepted compilation copy and protected identity; stock `Td.cpp`
is not a substitute for the candidate. Compilation provenance is not actual-engine proof.

The pinned parser API and C++ grammar were reused unchanged. Four whole functions remain
**uncertified**, not silently omitted:

| Function in `td/telegram/files/FileManager.cpp` | Raw parser limitation | Consequence |
|---|---|---|
| `on_upload_ok` | Missing `>` reported at line4922 in a comparison | No complete upload-success call/order certificate |
| `on_download_error_impl` | Error at `SCOPE_EXIT` body, line5123 | Download error/restart/effect closure remains open |
| `on_generate_error_impl` | Error in the `SCOPE_EXIT` construct | Generation error/effect closure remains open |
| `on_upload_error_impl` | Error in the `SCOPE_EXIT` construct | Upload error/restart/effect closure remains open |

Failed captures are retained locally. Incomplete functions have no enumerated whole-function
call graphs in the consumed packet. Manual reading of such a body does not repair its syntax
certificate. No source masking, alternate grammar or compiler preprocessing was used to
manufacture a positive result. Private receipts, parser binaries and tooling remain outside
this publication; these counts are a report of local consumption, not public runtime tests.

## Query identity and removal

`FileManager.h` stores download/generate/upload queries in `td::Container<T>`.
`tdutils/td/utils/Container.h` encodes the slot in the high32 bits and generation/type in the
low32 bits. `decode_id` rejects an out-of-range slot or generation mismatch. Ordinary release
increments generation by256, clears the data and does not recycle a wrapped-generation slot.
This describes ordinary release/reuse, not all other Container APIs or a session-generation
contract. FileId, child link token and query ID remain different identities.

Each `FileManager::finish_*_query`:

1. Looks up the query and CHECKs that it exists.
2. Copies it and erases its container entry.
3. Resolves its current file node.
4. Clears the node's matching active query ID/flags/priority and returns `was_active=true`,
   or returns the removed query with `was_active=false`.

Thus `was_active=false` does not mean a duplicate token was safely discarded: a valid query
was still removed. Calling finish again with that removed token reaches a CHECK, not a
universal duplicate-tolerant acknowledgement API. This does **not** demonstrate that native
producers actually deliver duplicate terminals; the surrounding delivery paths matter.

The three public error wrappers check `is_closed_` before finishing the query and dispatching
to their error implementations. Their downstream implementations have the explicit raw gaps
above. In particular, do not infer that every error is terminal or that inactive results
cannot cause effects. Success paths can register locations or schedule work; callback return
is not proof of all effects, durable sync or physical resource release.

## Child creation, cancellation and terminal ownership

| Owner | Work/identity | Cancellation and terminal path | What it does NOT prove |
|---|---|---|---|
| `FileDownloadManager` | Container node + query-to-node map; downloader or from-bytes actor; optional resource-manager actors | `cancel` calls `on_error_impl(-1, Canceled)`; callback precedes `close_node`; close erases both indexes; hangup resets workers/resources and `try_stop` waits for stop flag plus empty nodes | Callback may already be queued while child hangup/destruction remains pending |
| `FileUploadManager` | Container node + query-to-node map; uploader or hash-uploader actor and resource manager | Same callback-before-close pattern; hangup suppresses forwarding via stop flag and waits for empty nodes | Upload failure need not be a readiness failure; ordinary wrapper can fall back from hash upload while not closing |
| `FileGenerateManager` | Query map containing an owned worker and query-token shared parent | `cancel` resets worker; map erasure occurs via shared hangup token and `do_cancel`; closing loop stops only when map empty | Reset is not immediate completion or an acknowledgement from every generated-work branch |
| `FileLoadManager` | Actor methods for synchronous file read/check/unlink; no query/cancel roster in its header | Method fulfills supplied promise; no custom lifecycle override in this class | A returned promise is not a manager-wide drain or scheduler/resource-stop certificate |

For download/upload, child callbacks retain token-bearing `ActorShared` handles, move terminal
handles into manager messages, and managers resolve the node using the link token. Missing
nodes are ignored on late callback/error paths. Manager-to-FileManager wrappers use ActorId
and send the relevant query ID; they do not carry a G1 admission/session generation.
Ordinary dispatch can run immediately under suitable scheduler conditions, rather than
always queueing. See the [Close/repair follow-up](native-g1-close-and-repair.md).

`finish_downloads` and `finish_uploads` remove request entries before invoking extracted
callbacks. `on_file_load_error` requests cancellation of all three work kinds and finishes
requests across aliases. That checked function does not close the uncertified error handlers
that call it, nor prove completion of the cancellation messages it sends.

## Generated-work branches are not equivalent

In `FileGenerateManager.cpp`:

- `#file_id#` generation delegates to a FileManager download. Hangup sends cancel-download
  back to FileManager and stops the generating actor; success can dispatch a lambda back to
  FileManager that then invokes the generation callback.
- Web generation dispatches `upload_getWebFile` through a callback actor. Success processes
  and saves file bytes before notifying the generation callback. This branch is not excluded
  merely by a text-only frontend.
- External generation can create a temporary file, publish generation-start, accept writes,
  promote generated output on finish, unlink on errors and publish generation-stop at teardown.
  A write/finish promise and the whole generating actor's lifetime are separate boundaries.

These are conditional source branches, not executed operations or permission to enable them.
A supported-mode/input exclusion needs an enforced predicate, not an assumption about UI use.

## Two concrete resource-proof gaps

1. `FileLoadManager::unlink_file` ignores the unlink status then fulfills Unit. That promise
   cannot certify deletion, private-data erasure or B2 purge success.
2. `FileDownloader::tear_down` and `FileUploader::tear_down` release per-part cancel signals.
   Downloader hangup may first reset a delay dispatcher and wait for its shared hangup before
   stopping. These are cancellation/lifecycle edges, not observed network quiescence, file
   descriptor closure or a worker/scheduler join.

The next integration boundary must distinguish terminal publication, logical ownership,
actual child/resource termination and clean persistence. Whole-owner closure, truthful native
stop/lease release and authenticated reopen remain unproved. No SDK, native fixture, actor
engine, account/provider or Telegram network operation was run for this source investigation.
