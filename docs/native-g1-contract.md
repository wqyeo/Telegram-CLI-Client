# Native G1 contract and implementation gates

Status: **operator-approved hardening target; NOT implemented engine-wide or certified.**
The approval following the recommendation to permit defined healthy-store maintenance
supersedes the earlier strict/no-maintenance direction for this new work. Earlier Batch A,
N1, publication and wiki receipts remain historical; they must not be rewritten as G1 proof.

## Contract

1. Create a native store only under explicit NEW intent in a pristine, app-owned location.
   An existing/partial/unknown store never becomes NEW because a component is missing.
2. Open an existing store only with its accepted current key/format/schema/flags. Wrong or
   missing keys, incompatible versions, ambiguous replacement files, missing components and
   recognized corruption/storage errors refuse; no automatic reset, repair, truncation,
   schema rebuild, replacement promotion, blank/old-key fallback or fresh login.
3. Permit normal transactions and journal/WAL lifecycle on an accepted valid store, and
   validated-store binlog compaction/replacement. This is not permission to treat repair as
   maintenance. Supported normal message updates/deletions/expiry are application data
   semantics, not a recovery reset. No user-facing delete/logout feature is added here.
4. Initially refuse unapproved migration, downgrade, compatibility conversion and key
   rotation. Do not silently broaden the supported version/format set.
5. On recognized runtime storage failure or authorization loss, stop operations and purge
   private application views. Preserve local evidence according to the reviewed boundary;
   never keep revoked authority usable merely by suppressing native destructive close.
6. Never reset/reprovision a profile, break a lock, auto-restart/authenticate, try blank
   credentials or use a backup/restore to claim mutation of the original was prevented.
7. This is NOT every-failed-open byte atomicity, detection of all corruption before every
   write, perfect erasure, same-user/root protection, or immunity from ordinary I/O failure.
   Healthy writes before later failure may remain. Crash-damaged stores may be refused even
   where upstream could repair them; no automatic recovery process is implied.

## First supported engine mode (operator decision)

Prioritize **binlog-only** for the first G1 engine: all three persistent SQLite-cache
flags (`use_file_database`, `use_chat_info_database`, `use_message_database`) are off
and must be pinned in accepted configuration. The native binlog still persists sensitive
session/engine data and must be encrypted; this is not a storage-free or relaxed-safety
mode. History/chat data can be refetched from the service, with less restart caching.

Existing mode mismatches or SQLite files/remnants must refuse without deletion, not be
adopted, silently disabled, migrated or treated as NEW. Merely passing false flags to the
unchanged SDK is unsafe: current TdDb still invokes SQLite destruction in that branch.
That path must be removed/gated before any activation. Full SQLite schema/NEW-table
support is deferred, not claimed complete. Retain accepted low-level SQLite hardening
for later cache support; generic component tests of other flag combinations do not
approve those combinations for the initial engine.

The operator additionally selected **filesystem-bound store identity** from trusted
profile/storage location and held directory device/inode context, and **exactly32-byte
raw-key interpretation** matching the pinned nonempty TdDb key path. Same-directory
reopen is supported; relocation/recreated-directory restores refuse. No new random
identity-anchor file is selected. Empty-key fallback must be rejected; no password
interpretation retry, key hash in metadata, migration or rotation. These are approved
targets, not a claim that production binding/orchestration is integrated.

The operator additionally approved an **explicit native main-DC schema rule**: NEW
initializes native's existing `main_dc_id` PMC key from the actual dispatcher state under
its existing DC-change mutex before native validation/sync/seal. EXISTING requires canonical
decimal1..1000 and refuses missing/malformed state without native-default guessing, repair
or adoption. Supported healthy native DC changes remain allowed; filesystem identity and
the120-byte admission wire format do not change. This is an approved target, not native
semantic/actor/account acceptance; all execution and activation gates below still apply.

Use a **separate versioned app-owned admission record**, not configuration inferred
from missing components or replay. B1d supplies bounded reservation/record primitives,
not production identity/configuration binding or real database validation/formatting.
Explicit NEW, existing-store key/format admission, healthy maintenance, no automatic
destructive recovery, authorization-loss stop/purge, and all activation/publication
gates remain required. This mode choice does not authorize an actual client/database.

## Implementation sequence

B1a first implements bounded low-level SQLite refusals against actual cached TDLib/SQLCipher:
corruption error handling (M1), missing-path refusal rather than orphan cleanup/create (M2),
and no implicit compatibility/old-key/rekey/export fallback (M4). A real native PUBLIC
fixture harness must demonstrate RED against unchanged libraries and GREEN against the
patched objects, including healthy write/close/reopen. This is not another synthetic peer.

This first component deliberately exposes **no NEW-store API**: legacy allow_creation does
not grant NEW intent in the experimental code. Explicit NEW orchestration and atomic file
reservation remain required before actual engine integration. PUBLIC test fixtures are
created separately by a labelled fixture generator, not by silently weakening this gate.

B1a SQLite, B1b existing-binlog replay, B1c maintenance, B1d admission-record
primitives, B1e explicit NEW encrypted-binlog-header formatting and B1f trusted expected
filesystem-bound/raw32 policy have separate local component receipts; none establishes
full engine G1. B1e initializes only an encryption service header, not production native
version/auth/configuration records. B1f derives expected policy but is not wired into
the profile/native coordinator and does not validate native/account data.
Next B1 work is full protected NEW initialization and binlog-only TdDb orchestration:
wire trusted policy into the caller, enforce current binlog format/version admission,
SQLite absence/mode refusal and removal of destroy/retry paths (M3/M8). Full SQLite
schema/NEW-table support is deferred with the optional caches. B2 separately covers
native auth-loss/restored-destroy/close actor ordering and revoked-authority safety
(M9/T12). B1 completion does not establish B2 completion.

Fixed native-source patches, provenance and tests belong in `native/hardening/`; apply them
only to a fresh private workspace. Preserve the original pinned source tree, cached build,
JNI/SDK bundle, old packages and default Maven artifact. No automatic SDK installation or
switching of the shipped native worker. Do not advertise the patched work as unmodified
upstream TDLib. Record base revision, patch digest, source hashes, cached-library/image
identity and compiler evidence; hashes are not security or reproducibility certification.

## Activation and publication gates

No `Client.create`, actual account engine/database, genuine input/provider/auth/private data,
live connection, sending or authenticated resume is authorized by this stage. Low-level
PUBLIC SQLite/binlog/actor fixture execution is allowed in isolated network-denied native
hardening tests. Actual-client and operator-local acceptance remain separate approvals.

No source commit/push/wiki/package/release, dependency revision change, installation,
provisioning, CI, terminal backend or Windows work follows automatically. Default builds
remain SDK-free. A native account client is NOT ready until full reviewed implementation
and runtime evidence meet this contract and the subsequent lifecycle gates.
