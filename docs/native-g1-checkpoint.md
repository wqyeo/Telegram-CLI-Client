# G1 native hardening — source checkpoint

**Work in progress, not a native-account-client release.**
`integrated_engine_accepted=false`; the guarded native factory remains fenced.
The normal Maven build stays SDK-free. This checkpoint neither replaces the existing JNI
bundle nor enables login, account access, sending, authenticated reopen or native Windows.

## What this commit preserves

- Experimental native patches, bounded helpers, PUBLIC fixtures and explicit opt-in runners
  in [`native/hardening/`](../native/hardening/) and `scripts/native/`.
- Java-owned profile lease/directory retention, one-use generation-bound native claims and
  PUBLIC cross-process lock tests. A Java stop-confirmation method is not itself native
  shutdown proof; actual worker/native lifecycle composition remains unfinished.
- The [native G1 target contract](native-g1-contract.md). Its publication section is an
  authority boundary: this source checkpoint was separately authorized; it does not grant
  account/runtime, release, installation, packaging, wiki or CI expansion permission.

The source patches are against TDLib revision
`a94b83d67ab4c8397c6245e541b31a4131ea2f79`, not against the application revision.
Apply only in a fresh private workspace with the required pinned inputs. Do not mutate the
original source/cache or install these objects as a replacement SDK.

## Evidence layers — do not conflate them

Earlier local component work independently consumed low-level PUBLIC storage/actor/codec
fixtures and bounded native-object/private-Java compilation. The latest such source/leaf
checkpoint is coordinator descriptor8, whose schema ID is
`0b5c2ac12d43fb58612a348e5c3a23f101a0604d79217ec396e355faea2442a0`.
It includes canonical authenticated identity and exact24-name absence predicates. This is
not evidence that actual TdDb initialization, owner completion, guarded publication or
account reopen executed correctly.

A later source-only compiler-preprocessing certificate covers the supported Linux dispatcher
input case. Raw parser limitations remain recorded; it is not all-platform or callback-owner
closure. A descriptor6 reserved-gap fixture failure remains **UNDETERMINED**: later passes do
not explain it or erase the retained failure.

Private stages, parent receipts, source-analysis tools and diagnostic failures remain local
under ignored `.pi/`; they are **not included in this commit**. Earlier component documents
and manifests retain their historical checkpoint wording and local receipt paths. They are
not independent public verification receipts or a claim that all checks ran on this commit.

## Five fixed roles: policies selected, validators not implemented

For a PRESENT value, require canonical integer encoding (`I` followed by the exact decimal
below), using already-bound trusted native production/test-DC policy. Never infer mode from
stored option bytes or substitute another structurally valid user ID.

| Exact option name | Production | Test DC |
|---|---:|---:|
| `telegram_service_notifications_chat_id` | `I777000` | `I777000` |
| `replies_bot_chat_id` | `I1271266957` | `I708513` |
| `verification_codes_bot_chat_id` | `I489000` | `I489000` |
| `group_anonymous_bot_user_id` | `I1087968824` | `I552888` |
| `channel_bot_user_id` | `I136817688` | `I936174` |

For ABSENCE: all five original entries are required before EXISTING consumption/defaulting
and at seal capture and same-binlog-actor post-sync revalidation. Only explicit pristine NEW
may initially lack them and use normal native production. Missing/invalid EXISTING state
refuses without reconstruction, normalization, repair, fresh login or BUSY/CHANGED retry.
Present empty bytes, S-empty, I0 and a missing key are not interchangeable.

Five integer present/absence contracts are selected. The remaining120 integer present and
absence contracts remain open. This does not select other cleanup, unknown-name, migration,
mutable server-value, initializer API or remote-effect policies. The five-role implementation
is held for the combined A1/A2 integration rather than another isolated leaf milestone.

## Main unfinished integration

- Finite supported schemas, original-state guards, replay/effect dispositions, initializer
  owners/children, required-local subset and real completion acknowledgements.
- Production observed-snapshot/wake ticket transfer, EventFd scheduling, one-flight attempts,
  fresh BUSY/CHANGED captures, semantic-refusal precedence, late-generation and Close handling.
  No production polling interval, retry deadline or fairness guarantee has been selected.
- Native-written special-set metadata versus SQLite-off startup erasure, paid-reaction
  dependencies and pending service mutations. Media UI deferral does not exclude native
  option parsers: emoji-sound handling can still log/decode/check/register files.
- Durable authorization/storage-loss revocation, private-view purge, nondestructive native
  Close and positive resource-stop/worker-exit/sole-Java-lease/reopen composition.
- Typed guarded shared ordinary-conversation reader and privacy/log containment.

Coherent full SDK build/link, actual PUBLIC/network-denied account engine, genuine local
profile/network reads, live sends and publication of packages/releases remain separately
gated. Internal Ready, an init flag, constructor return, actor hop or Java acknowledgement
cannot replace those gates.

## Verification and reproduction boundary

The publication checkpoint runs the SDK-free default JVM suite in a fresh isolated copy and
explicit Python runner/oracle mocks. These do not run TDLib account engines or prove the
unfinished native integration. Native evidence is reused only at its recorded component
layer; no unchanged native fixture/compiler rerun is represented as new progress.

Manual mock commands (not part of the default Maven path):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/native -p 'test_g1_sqlite_runner.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/native -p 'test_g1_context_oracle.py' -v
```

Actual low-level native runners require the exact already-cached Linux builder, archive,
static libraries and toolchain described by their manifests. The coordinator compilation
runner additionally requires a separately prepared private source/preflight/provenance
stage; this checkout does not supply those local records. Missing prerequisites fail closed,
with no provisioning, image pull, default SDK switch or automatic engine activation.

The source checkpoint is SSH-signed and pushed with `ci.skip`; a skipped pipeline is **not**
CI success. No tag, release, package, wiki update or native artifact publication is included.
