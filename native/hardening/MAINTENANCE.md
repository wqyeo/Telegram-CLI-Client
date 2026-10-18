# Experimental B1c — normal binlog compaction and fail-stop boundaries

**In progress; not engine-wide G1 or SDK activation.** This patch is layered after the
hash-identified B1b load/replay patch, never applied to the original cached source tree.
The approved target remains [`native-g1-contract.md`](../../docs/native-g1-contract.md).

Linux maintenance changes:

- Compaction creates `.new` exclusively (`Write | CreateNew`, NOT `Create | CreateNew`;
  the pinned port's `Create` branch would otherwise bypass `O_EXCL`). Never truncate an
  existing replacement remnant. Failure stops the native process rather than returning
  from the legacy void compaction path and continuing.
- After writing and syncing the new file, replace the accepted main with one Linux rename.
  Do not unlink the only main before a potentially failing rename; retain its old fd/lock
  until replacement succeeds. A rename error stops, leaving main and replacement evidence.
- Refuse explicit key rotation before mutation. The legacy void API cannot return refusal,
  so this experimental boundary stops the process with a fixed error marker.

These fail-stops are abnormal native failures, NOT successful Close or a durability proof.
The PUBLIC harness disables cores and confirms termination. Actual-worker integration and
error mapping remain future gates. There is no claim of directory-fsync/crash atomicity,
all-bytes-unchanged runtime failure, or immunity to ordinary I/O failure. Accepted healthy
writes before later failure may remain; refusing compaction can lose buffered availability.

```sh
# Fresh opt-in stage each time; inspect RED before GREEN, never retry automatically.
python3 scripts/native/test-g1-maintenance.py --variant stock
python3 scripts/native/test-g1-maintenance.py --variant g1
```

The unchanged reviewed container controls remain in use. The suite repeats all 13 B1b replay
cases against the cumulative patched object, plus five real maintenance cases:

1. Automatic threshold-triggered compaction changes the main inode and retains three
   records after write/close/reopen under the same accepted key.
2. A replacement sentinel created AFTER opening must retain bytes/inode; stop on collision.
3. A replacement directory created AFTER opening must survive; stop rather than continue.
4. A fixture-only linker wrapper makes POSIX rename return EIO during compaction. The main
   inode must remain and `.new` evidence must survive. No preload/install or genuine data.
5. Explicit key rotation must stop with the expected marker and unchanged fixture tree.

Only the deliberately injected refusal paths accept SIGABRT, and only with their exact
fixed marker plus evidence checks. An unrelated crash/timeout is not a pass. The collision/
directory/rename cases permit prior healthy main writes; they verify evidence inodes, not
byte atomicity. The sentinel is created by the PUBLIC fixture and its pre-trigger inode is
flushed to stdout for comparison. Plain source-review/LSP success is not native acceptance.

Protected NEW orchestration, TdDb schema/flag/version and all-store admission, full path/
lease/ACL/log/error handling, explicit destroy callers and native authorization-loss actor
ordering are still unfinished. Source/package/publication and actual Client/account trials
remain excluded. Do not use the test executable as the shipped JNI bundle.
