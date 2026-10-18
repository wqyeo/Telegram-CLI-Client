# G1 trusted binlog-only expected policy (B1f)

Status: **trusted expected-policy component accepted locally; not integrated**.
Parent acceptance consumed 304/304 cumulative checks, 271 required read-only trees,
independent identity/wire reconstruction, build/lifecycle/preservation evidence and
fresh source review with no issues. This accepts the bounded policy helper and
manual harness, not a production coordinator or engine-wide G1.
No production coordinator, TdDb/account execution, client, JNI or SDK activation.
The accepted B1a–B1e receipts remain immutable and cover their earlier hashes.

## Approved policy

The operator chose filesystem-bound non-secret identity and exactly32-byte native
raw-key interpretation. All3SQLite-cache flags remain off. The separate admission
record is compared against independently derived expected configuration, never
used as its own authority. No adoption, repair, migration, key rotation, empty-key
or alternate-interpretation fallback is permitted.

`BinlogOnlyPolicy::bind` takes an already trusted, exclusively leased profile
context: ASCII profile name, normalized absolute dedicated namespace path ending
`<profile>/native-g1`, its held directory fd, and explicit expected DC. The future
caller must establish trusted ancestors and exclusive profile ownership first;
this helper does not acquire a profile lease or claim protection against the same
user/root. It walks every path component with nofollow directory opens, checks
private0700/effective-UID profile and namespace directories and fd/path inode
correspondence, and retains its own descriptors. Expected configuration, borrowed
fd and key admission revalidate the context. Moved-from policies refuse.

The16-byte store ID is the first16bytes of SHA256 of these **public** bytes:

1. ASCII `telegram-cli:g1:filesystem-store-id:1` followed by LF.
2. LE32 byte length + profile-name bytes; LE32 byte length + absolute namespace-path bytes.
3. LE64 profile device, profile inode, namespace device, namespace inode, in that order.

No timestamps, native contents, keys or key hashes enter this derivation. Normal
same-directory reopen/content maintenance preserves identity. Relocation or a
recreated profile/namespace directory refuses. This is intentionally non-portable;
not a signature, rollback detector, universal clone detector or durability claim.

Expected cache bits are unconditionally0; only DC bit8 comes from trusted explicit
caller context. `schema_version=1` is the app-owned binlog-only layout-schema policy
revision, **not** upstream SQLite database version14. `schema_id` is SHA256 of the
compiled exact `BinlogLayoutDescriptor` bytes: wire/layout policy, fixed namespace/
slot/component names, filesystem identity recipe, raw32 interpretation, native
revision and accepted B1b/B1c/B1e patch digests. The manual builder checks those
actual patch/reference hashes. This pins the intended implementation/configuration;
it does not validate native/account records or replace the future intrinsic native
version/configuration admission and NEW initialization.

The key gate accepts only `DbKey::raw_key` with exactly32bytes. Password, empty,
short/long and the stock empty-input `cucumber` fallback refuse. Key bytes are not
logged, hashed or placed in metadata. Vault generation already supplies32random
bytes; no genuine factory/vault/input is invoked here. **TdDb::as_db_key's old
fallback branch is still unchanged**: orchestration must eliminate/gate it before
activation. This component cannot make the current SDK safe by itself.

The factory performs no record/database reads, formatting, writes, cleanup,
sealing, NEW-capability minting or client/provider calls. Deriving identity for an
unknown directory is not store admission. A production NEW coordinator must
exclusively create a fresh dedicated namespace; opening an empty existing namespace
is not NEW. EXISTING must pass the separate exact record/physical gate and actual
native key/format/version/schema validation before any application exposure.

## Manual PUBLIC validation

```sh
python3 scripts/native/test-g1-policy.py --variant g1
```

G1-only new API, no stock behavioral RED claim and no default verification hook.
Use the existing cached network-none builder, read-only inputs/cache, output-only
writable mount, exactCID/confirmed-stop-before-preservation controls and fresh
private copied native source. No original sources/libraries/JNI/JAR mutation.

The accepted run passed304checks:257B1e cumulative regressions plus47policy checks.
Independent Python recipes verify schema/store IDs and exact wire bytes. Cases
cover real fd/path/profile identity, restart-like rebind, moves/clones, nofollow
ancestors, mode/shape failures, moved/retained capability lifetime, exact raw32 key
shape, record/SQLite/remnant refusal and post-bind moved/replaced contexts. A PUBLIC
header-only native roundtrip and wrong-key refusal use the actual layered binlog;
ordinary record fixtures use explicitly labeled **non-database dummy mains**.
No account/native version/auth records are initialized or certified.

A bounded PUBLIC barrier snapshots after fixture setup, before tested operations.
Exact exits/stdout/stderr/binlog-open/callback counts are required; no timeout,
signal or unrelated crash passes. Named post-bind fixture mutations are separately
checked, not misreported as unchanged. The source/fixture/helper extension has its
own B1f acceptance; historical B1a–B1e receipts were not rewritten. Later extensions
require affected validation and review again.
