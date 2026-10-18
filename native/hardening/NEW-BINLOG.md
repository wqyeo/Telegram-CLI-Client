# G1 explicit NEW encrypted-binlog component (B1e)

Status: **encrypted-header component accepted locally; not integrated**.
The parent inspected 257/257 cumulative checks, 229 required read-only trees,
fault evidence/build/lifecycle/preservation receipts and a fresh source review
with no issues. This accepts only the bounded header formatter and reservation
extensions, not production NEW-store orchestration or engine-wide G1.
Not integrated into TdDb, JNI, a client, the account worker or an activated SDK.

The operator selected binlog-only for the first supported engine mode, with all
three SQLite-cache flags off. Preserve the accepted SQLite work for later optional
cache support. This is still sensitive persistent native storage, not a safety
exemption. `docs/native-g1-contract.md` remains authoritative.

## Boundary

`binlog-new-g1.patch` layers AFTER the unchanged B1b replay and B1c maintenance
patches on fresh copies of the pinned source. It adds a static
`Binlog::create_new_encrypted(NewReservation &, DbKey)` API, without changing
existing signatures, object layout or the existing-entrypoint refusals.

The caller must already own the exclusive profile lease and a live explicit NEW
reservation in a dedicated pristine app-owned namespace. Production identity,
accepted configuration, serialization/schema/version binding and authorization
policy remain caller/coordinator responsibilities. The API:

- Consumes the reservation's one-shot formatter handshake, including failed
  preflight. Requires the root to contain only its exact pending 112-byte record.
- Refuses SQLite-cache flags and empty/non-32-byte keys. Uses precisely the caller's
  requested TDLib raw-key or password interpretation, never an alternate/blank key.
  Production key-interpretation policy is not selected by this fixture API.
- Derives `td.binlog`/`td_test.binlog` from immutable configuration. Anchors its
  temporary path under the held root fd; accepts no arbitrary output path.
- Uses `Read | Write | CreateNew` (`O_CREAT | O_EXCL` on the pinned Linux port),
  checks the fresh fd/path inode, owner, regular type, single link and private mode.
- Uses pinned TDLib encryption-event, random salt/IV, KDF and CRC serialization to
  write only an encryption service header. Handles short writes; error/zero write,
  sync and unlock failures refuse. No `.new`, truncation, rename, promotion,
  deletion, rollback or automatic retry.
- Closes the writer, reads back with the hardened existing entrypoint and exactly
  the requested key, requires zero user-record callbacks, closes that verifier,
  and rechecks configuration/component presence before completing the handshake.
- Returns with all temporary binlog handles closed. Does NOT append the admission
  completion tag. A future coordinator must initialize/validate production native
  version/configuration metadata before explicitly sealing and on every open.

The admission header gains an additive in-memory formatter latch; wire version
and 112/120-byte encoding are unchanged. Failed/unfinished formatting cannot seal;
`fail_formatting()` can also latch a later coordinator failure before sealing.
The old generic record primitive remains a configuration/presence gate, not a
native database certificate. Historical B1d acceptance covers its earlier header
and runner hashes, not these extensions; its receipt remains immutable.

Failed creation may leave an empty, partial or complete new main and a pending
record as evidence. No clean-close, byte-atomicity, all-corruption detection,
crash/directory-fsync durability, erase or recovery guarantee is made. Ordinary
TDLib failures outside Status channels may still fail-stop; this is not engine
lifecycle/error-mapping implementation.

## Manual PUBLIC checks

```sh
python3 scripts/native/test-g1-binlog-new.py --variant g1
```

No default Maven/Python verification registration. The new API has no stock
implementation: no stock behavioral RED is claimed. Cached network-none builder,
read-only sibling inputs/cache, output-only writable mount and unchanged exact-CID
stop/remove-before-preservation controls are reused. Never rebuild/pull the image,
mutate original sources/libraries/JNI or switch the SDK.

The cumulative suite compiles the layered native objects and executes 13 replay
and 5 maintenance checks, 33 NEW checks, and the original 206 record regressions
against the extended header. The record runner accepts only its original stage or
a fixed nested regression stage; its normal manual entrypoint is unchanged.

NEW controls cover raw/password/test-DC creation, existing admission/write/reopen,
wrong-key/no-callback refusal, repeated capability use, existing/partial slots,
unknown/moved evidence, links/FIFO/modes/record mutations, unsupported cache flags,
invalid keys, failure latching, and fixture-only link-wrapped open/write/zero-write/
partial-write/sync/readback failures. A bounded PUBLIC pipe barrier permits actual
before/after snapshots around formatting, not around fixture setup. Timeout,
signal or unrelated crash is never a NEW-refusal pass. Partial-write evidence is
explicitly permitted and checked, not reported as unchanged.

Fixture identities/schema version 77/ID bytes are PUBLIC placeholders, not
production schema approvals. The encryption header is a real low-level TDLib
binlog format; no account records, credentials, private content, TdDb, actor account
engine, `Client.create`, providers, network, actual database/login, publication,
SDK activation, CI or package work is authorized or performed by this component.
Remaining gates: trusted production binding; binlog-only TdDb/version/flags/SQLite
absence orchestration and destroy/retry elimination; authorization-loss stop/purge;
then separately approved actual-client and operator-local lifecycle acceptance.
