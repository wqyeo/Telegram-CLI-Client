# Four-function source preprocessing proposal — NOT EXECUTED

This is a bounded feasibility proposal, not permission to run the compiler, acceptance of
four functions, or an SDK build recipe. `integrated_engine_accepted=false` and the factory
remains fenced. See [query lifecycle](native-g1-query-lifecycle.md) for the retained raw gaps.

## Target and expected value

All four functions are in `td/telegram/files/FileManager.cpp` at TDLib revision
`a94b83d67ab4c8397c6245e541b31a4131ea2f79`:

| Function | Raw issue | What preprocessing might establish |
|---|---|---|
| `on_download_error_impl` | `SCOPE_EXIT` syntax | Real macro expansion and the flush object's scope, plus the error/restart branches |
| `on_generate_error_impl` | `SCOPE_EXIT` syntax | Expanded cleanup scope and terminal/error effects |
| `on_upload_error_impl` | `SCOPE_EXIT` syntax | Expanded cleanup scope and restart versus terminal behavior |
| `on_upload_ok` | Comparison parsed as an incomplete template, line4922 | No promised fix: preprocessing need not change the ambiguous comparison |

`tdutils/td/utils/ScopeGuard.h` defines `SCOPE_EXIT` as a uniquely named ScopeExit object plus
capture lambda. Expansion could remove that raw grammar obstacle. It could also expose other
parser limitations. It is not a C++ semantic/type check, and cannot be assumed to repair the
upload-success comparison. A compiler AST/typecheck or changed parser would be a different
proposal, not an automatic fallback.

## Available command provenance versus missing provenance

The accepted Linux dispatcher preprocessing record uses GCC13.3, C++17, PIC, no exceptions/
RTTI, `_FILE_OFFSET_BITS=64`, `_GNU_SOURCE`, candidate overlay headers, pinned original TDLib
and generated headers, with `-E -dD -MD -MF` and a fresh output path. The bounded coordinator
compile command for the dispatcher independently records the corresponding include order.

These are **dispatcher commands, not an accepted FileManager translation-unit command**.
The current cached source tree retains a FileManager object, but its expected CMake
`tdcore.dir/flags.make` and `build.make` files were not present during this preparation.
An object alone is not command/configuration/header-closure provenance. The LSP compile
database uses a diagnostic sysroot and is not a replacement native build certificate.
No missing recipe/configuration was reconstructed or installed for this proposal.

A future FileManager command can therefore be labeled only a **derived candidate**, until
its exact arguments, input identity and supported configuration have been separately bound.
The earlier dispatcher certificate does not certify another translation unit.

## Proposed isolated command transformation

Subject to approval and successful preflight:

1. Preserve the recorded compiler, language/feature defines and include ordering. Freeze the
   derivation from the accepted dispatcher argument vector; enumerate every changed argument.
2. Replace only the translation-unit path with a fresh copy of the pinned FileManager source.
   Retain the accepted candidate overlay where included, plus original/generated dependencies.
   Do not alter source bytes or insert header stubs to make parsing succeed.
3. Keep preprocessing-only `-E -dD`. Write dependency output with `-MD -MF` and preprocessed
   output to fresh private files. Remove object-generation arguments; no link/archive/build
   invocation, CMake reconfiguration, generated-code regeneration or compiler syntax execution.
4. Use only the already-cached immutable rootless Linux builder and pinned compiler/toolchain.
   Original source, dependency/cache and toolchain mounts remain read-only; scratch output is
   isolated. No image pull, install, network, host-toolchain fallback or runtime/client action.

A private, non-executed candidate argument vector now preserves the recorded dispatcher
vector with exactly three substitutions: target source, dependency output and preprocessed
output. No compiler flag was added or removed. This is an argument derivation, not an
accepted FileManager recipe or mounted-input preflight. No runnable container invocation is
supplied: FileManager's feature/header binding and execution approval remain unestablished.

## Preflight and evidence contract

Before any future invocation, independently record:

- Current application commit and selected source/overlay/checkpoint identities, without
  reinterpreting historical source receipts as current runtime acceptance.
- The immutable image identity, compiler identity and exact argument-vector derivation.
- FileManager's archive-exact bytes and the actual copies mounted at every include root.
- Generated configuration/API headers and feature defines for this Linux case. A macro seen
  in another translation unit is not automatic certification of FileManager's configuration.
- A preservation baseline for originals, prior failed/accepted packets and native/default
  artifacts. Never reuse an already consumed exclusive-output stage.

The future output packet must retain stdout/stderr, exit status, exact arguments, preprocessor
line markers, macro definitions, dependency records and hashes of consumed non-image inputs.
Record compiler built-ins/immutable image provenance separately from repository headers.
Missing, changed, unexpected or ambiguous dependencies refuse acceptance; no header repair,
blank fallback, source synthesis or relaxed parser checks.

## Mapping back to the four functions

For each function separately:

1. Retain the original raw body/range/hash and original parser failure.
2. Identify its preprocessed definition using compiler line markers and surrounding mapped
   declarations, not a text search hit assumed to identify the right overload/body.
3. Preserve the original-to-expanded mapping, including ScopeGuard/lambda boundaries and
   macro-generated calls. Do not assign all expanded tokens to the original invocation's
   literal byte range.
4. Require complete syntax for any proposed whole-function call/order certificate. If any
   ERROR/missing node remains, that function stays uncertified; usable other functions do
   not convert it into a pass.
5. Independently consume hashes, source membership, mapping and selected ranges. Separate
   expansion/call-order evidence from semantic/type validity, actual child execution and
   physical resource-stop proof.

Stop on a failed compiler invocation, unmappable definition, incomplete dependency identity,
unsupported feature branch or residual syntax gap. Retain the failed stage. Do not rerun
until green, change source/grammar or escalate to object compilation without approval.

## Decision-ready boundary

A possible next authorization is one fresh, preprocessing-only Linux FileManager invocation
using a reviewed derived command, followed by bounded source/mapping consumption. It must
explicitly acknowledge that no accepted FileManager command currently exists and that
`on_upload_ok` may remain unresolved. Approval of this document does not execute that step.

Even a successful result closes only the corresponding source-capture gaps. It selects no
D1-D4 policy/roster/API, implements no validator/barrier, explains no original reserved-gap
fixture failure, and proves no engine startup, shutdown, lease release or authenticated
reopen. Those boundaries remain unchanged.
