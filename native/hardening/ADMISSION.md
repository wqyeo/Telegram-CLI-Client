# G1 separate admission record — B1d primitives

Status: **B1d base and B1e formatter extension accepted locally; not integrated**.
The base standalone PUBLIC native checks and source review passed. Its immutable receipt
covers the earlier header/runner hashes, not the new formatter handshake. The extension
has separate B1e runtime/review acceptance, described in `NEW-BINLOG.md`. This is not TDLib data
validation or engine-wide G1 acceptance. The operator
selected a separate app-owned, versioned admission record rather than storing the
accepted configuration inside native replay. This does not authorize an account
client/database, shipped SDK switch, adoption/migration, or publication.

## Boundary

`store-admission.h` is a Linux-only, header-only filesystem/configuration primitive.
It does not call TDLib, parse a database, validate keys or SQLite schema, replay
records, authenticate, hold a profile lease, or invoke providers. It is not yet
wired into TdDb or the shipped worker. Original cached sources remain unchanged.

The caller must already hold exclusive ownership of the profile and supply a
trusted directory fd for a **dedicated private storage namespace**, not a shared
profile root. NEW requires this namespace to be empty; EXISTING requires exactly
its requested slot. Other/moved evidence refuses. The helper checks effective UID,
0700 directories, 0600 single-link regular files, fd/path inode correspondence,
fixed allowed component names and configuration equality. It rejects symlinks,
nonregular/empty mains, `.new`, unexpected files, and mismatched DC/flags. Normal
SQLite WAL/SHM/journal sidecars may exist on the configured SQLite store.

`Configuration` must come from trusted expected policy/profile context, not from
blindly decoding and echoing the same record. Production binding of the non-secret
store ID to profile identity, and the actual pinned schema/layout fingerprints,
remain coordinator work. No key, key hash, credential or account content belongs
in this record. The record is not a signature or same-user/root defense.

## Wire format and construction

`g1-admission` is exactly **120 bytes**, little-endian integers:

| Offset | Size | Meaning |
| --- | --- | --- |
| 0 | 8 | `TGCG1ADM` |
| 8 | 4 | record version 1 |
| 12 | 4 | G1 native-format/layout policy version 1 (not an upstream version) |
| 16 | 4 | expected positive schema version |
| 20 | 4 | accepted flags: file=1, chat=2, message=4, test DC=8 |
| 24 | 16 | non-secret store identity |
| 40 | 40 | pinned native revision, ASCII |
| 80 | 32 | non-secret expected schema/layout identity |
| 112 | 8 | `G1SEALED` completion tag |

Flags must obey message ⇒ chat ⇒ file and contain no unknown bits. Identity and
schema/layout ID must be nonzero. Native format/record version/revision are pinned
by the implementation. Every encoded byte is compared with the expected policy.

`NewReservation::reserve` explicitly creates a fresh slot using `mkdirat`, then
creates the record using `O_EXCL|O_NOFOLLOW`, writes and syncs only its 112-byte
header, and retains the parent/root/record descriptors. An existing slot, even an
empty/partial one, cannot be reserved or resumed. Destruction only closes fds;
it never cleans up or rolls back failed evidence.

The trusted initializer must create and validate the actual components through
reviewed NEW formatting APIs. The experimental B1e extension provides one-shot
`begin_formatting()` / `finish_formatting()` and latched `fail_formatting()`;
failed/unfinished formatting prevents sealing. It adds no wire fields and performs
no native data validation itself. Binlog-only is the initial engine target;
other valid flag combinations here remain generic component grammar, not engine approval. It may then call the live, move-only
reservation's `commit_configuration()` **once**. The method rechecks namespace,
identity, expected header and physical components, appends the fixed completion
tag, syncs and rechecks. It never truncates, renames, overwrites another record,
reopens a failed reservation, or retries a failed commit. A sealed configuration
is not a database-integrity certificate. Existing opens are read-only record/
physical-presence checks and must be followed by native key/format/schema/data
validation before replay/application exposure.

This is not crash/directory-fsync atomicity, rollback, all-corruption detection,
or every-failed-open byte atomicity. Sync failure may occur after a write; the
current operation refuses and stops. Later explicit admission still needs full
native validation. The caller owns ancestor containment, lease/lifecycle and
permission/ACL policy beyond these checks. Same-user/root interference is outside
the security claim.

## PUBLIC component validation

Run manually, separately from Maven/default verification:

```sh
python3 scripts/native/test-g1-admission.py --public-record-fixtures
```

The host uses the exact cached builder and the accepted read-only-input/sibling-
output/exact-CID/confirmed-stop lifecycle controls, preserving earlier component
sources, original native references/libraries/JNI and the default JAR. Each run
gets a fresh retained stage; no pulls, native-cache changes, cleanup/retry fallback,
source publication or SDK installation.

The fixture compiles with GCC C++17 and `-Wall -Wextra -Werror -pedantic`. These are
**native filesystem/record tests, not TDLib database tests**: dummy component bytes
are explicitly `PUBLIC_NON_DATABASE_FIXTURE`; the fixture schema version and ID
are placeholders, not an approved production schema. Nothing links TDLib or creates
an account database. This new boundary has no stock-upstream implementation; no
stock behavioral RED result is claimed.

Coverage includes pristine reservation/one-shot commit, full/binlog-only/test-DC
configuration, every individual record-byte mutation, exact bounds and mismatches,
missing/partial/unknown stores, symlinks/hardlinks/FIFO/directory/mode cases, missing
or empty components, remnants, moved/replaced reservation evidence, and read-only
preservation. Backups/alias targets are outside the dedicated namespace but inside
the disposable case and preservation snapshot so early namespace refusal does not
mask the intended record/component link checks.

Remaining G1 work: trusted expected identity/configuration binding, real schema
allowlists and NEW formatters, TdDb all-store ordering and destroy/retry removal,
schema/flag/version no-rebuild behavior, explicit destroy boundaries and native
authorization-loss actor lifecycle. B1a/B1b/B1c success is not proof of those gates.
