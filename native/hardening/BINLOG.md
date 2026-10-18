# Experimental B1b — existing-binlog admission and replay

**Implementation in progress; no engine-wide G1 or SDK activation claim.** See
[`native-g1-contract.md`](../../docs/native-g1-contract.md). B1a SQLite acceptance is a
separate component, not proof for this patch.

This Linux-only patch covers the existing-binlog load/replay boundary (M5/M6):

- Refuse missing, empty or nonregular main files. Do not promote `.new` or create a main.
- Refuse existing/uncertain `.new` remnants, including dangling links, without removing them.
- Use only the requested current key; no old-key fallback or startup encryption reindex.
- Propagate record/parser/processor/partial-tail errors without repair/truncation or hex dumps.
- Check the complete committed record stream, offset and requested encryption mode before
  publishing replay callbacks. This is validation at the binlog layer, not acceptance of
  every SQLite/schema/account component. Legacy per-record debug callbacks are refused.
- Retain existing public signatures/class layout; two private helpers now propagate Status.
  The paired modified header is compiled before cached include roots. This is not an ABI
  certification or a substitute for rebuilding/reviewing the eventual complete native engine.

A separately linked, explicitly labelled **stock PUBLIC generator** creates disposable seed
files. The G1 executable cannot use missing files as NEW intent. No Client/account engine,
real keys/credentials, network, account database or private message content is used.

```sh
# Inspect expected behavioral RED before running the other variant.
python3 scripts/native/test-g1-binlog.py --variant stock
python3 scripts/native/test-g1-binlog.py --variant g1
```

The host runner reuses B1a's reviewed CID/deadline/termination/preservation controls without
modifying them. Only fresh `output/` is writable; frozen inputs/host receipt/CID have no
writable alias. Each invocation reserves a new private `.pi/native-g1-binlog-<variant>-*`
stage; inspect `host.json`, `output/result.json`, build logs and exact hashes. Missing tools,
source/archive/image mismatch, uncertain lifecycle or failed preservation refuses without
pull/install/fallback/retry. Old stages, cache/SDK, default JAR and packages are retained.

Thirteen bounded cases cover missing/empty mains, replacement promotion/ambiguity, malformed
tail/CRC, processor ordering, uncommitted partial transaction, wrong/old key, implicit format
change, debug callback and healthy encrypted write/close/reopen. The failing-load cases
require zero replay/debug callbacks and identical fixture trees. Healthy writes intentionally
change data and require three retained records on reopen. This is not universal failed-open
byte atomicity, detection of every semantic corruption, or same-user/root protection.

## Still pending

Normal runtime compaction/replacement and its failure behavior (M7), explicit key rotation,
explicit destroy/close-and-destroy callers, protected NEW orchestration, complete path/lease/
ACL/error/log handling, TdDb schema/flags/version orchestration and authorization-loss actor
work remain separate unfinished gates. This component does not claim to forbid every
mutation through every public Binlog API. Do not relabel it whole-engine compliance or
use its G1 binary as the shipped JNI bundle. No publish/package/install step is included.
