# Experimental G1 native hardening — B1a SQLite components

**Not engine-wide G1, not an account client, and not a replacement JNI/SDK bundle.**
The approved target and remaining gates are in [`docs/native-g1-contract.md`](../../docs/native-g1-contract.md).
The latest opt-in coordinator source increment is the [fenced descriptor10 passive-guard candidate](../../docs/native-g1-passive-guards.md): joined source guards, fourteen compiled native objects
plus private Java, and PUBLIC14 context/60 value families. This is not phase/engine acceptance;
the [descriptor9 five-role checkpoint](../../docs/native-g1-fixed-roles.md) and earlier component
results below remain historical. The factory stays fenced.

This first patch removes automatic corruption destruction from `RawSqliteDb::last_error`,
refuses missing/inaccessible/empty SQLite main files without orphan-sidecar cleanup or
legacy `allow_creation`, and removes compatibility/old-key/export/rekey fallbacks. The
original pinned source, cached build and existing native bundle remain unchanged.

The patched objects are compiled in a fresh private workspace and linked into a PUBLIC
native test executable with the existing cached TDLib/SQLCipher libraries. This is actual
low-level native storage execution, not an in-memory application peer. No `Client.create`,
TDLib account engine/database, account identity or network authentication is used.
Plaintext and password-labelled PUBLIC SQLite fixtures are generated explicitly by the
test harness; that generator is NOT an approved NEW-store entrypoint for the engine.

## Explicit manual verification

Prerequisites: Linux x86_64, Python3, rootless Podman, the exact already-cached builder image
recorded in `base.json`, and the original source/archive/static-library cache from the
existing native build. Missing/mismatched prerequisites fail; no pull, rebuild, install,
Maven SDK install, dependency pin change or tool fallback. These commands are opt-in and
are never invoked by the default Maven or Python verification paths.

```sh
# Expected RED on unmodified upstream behavior: inspect actual per-case failures.
python3 scripts/native/test-g1-sqlite.py --variant stock

# Experimental patched component result; not proof of whole-engine compliance.
python3 scripts/native/test-g1-sqlite.py --variant g1
```

Each invocation reserves a different `.pi/native-g1-sqlite-<variant>-*` directory with
separate sibling `inputs/` and `output/` directories, compiler/patch logs and a per-case
`output/result.json`, plus a host-only `host.json` preservation receipt. Never reuse/delete a failed stage to make it look green.
A build/setup failure is not the expected behavioral RED. Read the output receipt before
proceeding to the next variant; there is no automatic retry or default SDK switch.

The container has no network, read-only root/cache, dropped capabilities, no-new-privileges,
CPU/memory/PID limits, disabled core dumps and a private bounded tmpfs. Only `output/` is
writable; the frozen-input mount has no writable parent alias, and the host receipt and
CID file are not exposed through it. Podman also enforces a 570-second container deadline.
After the 600-second host timeout, the runner sends KILL to its exact recorded container
ID, then waits and inspects before finalizing any preservation evidence. An unconfirmed
termination or cleanup fails without retry; artifacts and the CID are retained. Removal
of the stopped container does not remove its bind-mounted fixtures. No home/credentials/
account profiles are mounted. This is bounded containment, not security certification.

Offline PUBLIC runner mocks (no container/native execution):

```sh
python3 -m unittest discover -s scripts/native -p 'test_g1_sqlite_runner.py' -v
```

Twelve cases cover actual SQLITE_CORRUPT(11), orphan-sidecar preservation, missing/empty
main refusal, compatibility fallback/explicit mode refusal, wrong-key refusal, no
rekey/decrypt/encrypt fallback, and positive accepted-key write/close/reopen plus current-key
opening. Refusal checks compare tree/bytes/size/mode/owner/inode, not kernel read-atime or
universal failed-open byte atomicity. `preserved` records actual tree equality even in
positive cases: successful healthy writes intentionally change bytes and do not require
preservation. PUBLIC remnants and failed fixtures are retained.

## Deliberate incompleteness

- No explicit NEW-store orchestration yet; a zero-byte existing file is not NEW intent.
- No complete native path/lease/default-ACL/error/log containment proof.
- `SqliteDb::destroy`/other explicit destroy callers are not yet removed from the engine.
- TdDb destroy/retry, schema/flag/version handling, binlog load/replay/repair/compaction and
  auth-loss/destructive-close actor work still require implementation and verification.
- No actual-client or live-account activation, installed artifact, signed release or
  native Windows evidence is implied. B1a results cannot be relabelled B1/B2 completion.

For optional host LSP analysis, `.clangd` points to an ignored local compilation database
at `.pi/g1-lsp`. The authoring session generated it using headers exported read-only from
the cached builder, without host package installation. Executable validation always uses
the actual pinned cached-builder compiler/libraries, not a claim based on LSP silence.
