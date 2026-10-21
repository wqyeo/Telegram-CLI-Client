# Fenced native passive-guard source increment

Status: **source/component checks passed; no actual-phase or engine acceptance**.
The factory remains fenced and `integrated_engine_accepted=false`. No operational CLI,
owned ClientManager/receiver integration, SDK replacement or account session follows.

The operator approved this joined source increment before remaining activation contracts
close. That changes implementation sequencing only. The [activation contract](read-only-activation-contract.md)
and [blocker register](read-only-activation-register.md) still govern activation.

## Implemented selected rules

| Store / exact key | Original supported state | Refused state |
|---|---|---|
| Config `default_reaction_needs_sync` | Absent or exactly `Bfalse` | Every other present value, including empty |
| PMC `top_peers_enabled` | Absent | Every actual member, regardless of value |

Raw quarantine and borrowed seal views use the selected predicates. PMC membership is
checked by actual membership/iteration, not `get().empty()`. EXISTING refusal precedes
export, constructor import and manager consumption. NEW still requires explicit pristine
input. Both seal passes retain semantic refusal before healthy Changed classification.

Guarded option writes check current markers before the new value, so erasure cannot repair
an observed invalid selected marker. Runtime erasure of a benign reaction marker remains
distinct from an original present-empty value. The distinct `default_reaction_need_sync`
spelling is not normalized or assigned a new policy. Broader option schemas remain open.

## Joined native placements

The implementation is distributed by `native/hardening/coordinator-g1.patch`:

| Owner | Candidate source behavior |
|---|---|
| TdDb | Original/import checks, current marker validation and both seal checks; no reset, clearing or adoption. |
| OptionManager | Common guarded writer/consumer path; changed post-init `disable_top_chats` writes refuse before map/PMC mutation. Public `online`/`disable_top_chats` requests return fixed errors before void-setter continuation or success. This is an operation boundary, not a new original-value schema for those controls. |
| OnlineManager | Refuse selected online/offline initiation before local status mutation/query allocation; independent `UpdateStatusQuery::send` fence. No offline RPC is used as a substitute for passivity. |
| ReactionManager | Check markers before initialization work; reject default-reaction operations before local change, handler allocation or successful promise settlement. Independently guard the RPC producer and result/error continuations before cleanup or redispatch. |
| TopDialogManager | Check markers before initialization state changes; refuse changed enable/disable operations and toggle initiation before flags, promises or handlers. Independently guard the RPC producer. Result/error continuations cannot erase intent, report defensive success or initiate resends in G1. |
| Td | Synchronously revoke context authority and coalesce one deferred Close request, avoiding constructor/child-actor Close re-entry. The atomic flag records scheduling only, never completion. |

Known literal producers of `account.updateStatus`, `messages.setDefaultReaction` and
`contacts.toggleTopPeers` were inspected in the three manager files. These are source
placements, not a complete transitive-effect certificate or observations of actual RPCs.
Non-G1 branches in the new Td refusal helper retain their prior source path; the separate
account-free execute probe and default artifacts are unchanged.

## Admission identity and compatibility

Descriptor **10** encodes the changed selected admission/effect semantics. The composed
layout-plus-coordinator schema ID is
`53da4e0bd572382b2cb0b2f257ba7188850f42106b1c3c45485f1ad968212b5f`.
Record sizes remain **112 pending / 120 sealed bytes**. Older descriptors are not adopted,
converted or repaired. Descriptor9 five-role receipts remain historical evidence.

## Bounded verification and limits

One cached rootless GCC13.3/Java25 component run compiled **14 native objects plus the
private Java wrapper: 15 checks, seven Java classes**. Then the existing PUBLIC components
passed **14 context cases and 60 value families**, including five new marker families.
Context authorization/seal handoffs are simulated; the new manager/constructor/seal/Close
phases were not executed. This is neither E1 coherent SDK acceptance nor E2 engine evidence.

Independent existing-output consumption matched 26 archive-original identities, the exact
coordinator patch and all three base-patch assemblies, compiled object hashes, exact PUBLIC
outcomes, records/schema and PUBLIC headers. Each stage preserved 556 protected identities;
442/446 frozen identities matched. Three shared objects were byte-identical. Both containers
positively exited and were removed; prior source stages and default JAR/JNI were unchanged.
No compiler/fixture retry occurred. Two preparation errors—schema digest composition and
an inline receipt-consumer bracket—were retained and corrected without rerunning components.

The active LSP probe was not clean: missing include configuration caused cascades on eight
candidate C++ paths; five checks were inconclusive and two clean. No suppression or dummy
headers were used. Component compilation supplies only its declared conditional type evidence.

Broader state/indirect-writer closure, the other 120 integer contracts, D3/value-attempt
completion, observed wakes/one-flight publication, runtime privacy/revocation and actual
resource stop remain open. Deferred Close, callback disposal, removed handles or process
exit are not physical-stop proof and do not authorize sole-lease release. The original
reserved-gap failure remains undetermined. E1, E2, F, sends and releases remain separate gates.
