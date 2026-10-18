# G1 exact native identity pair — descriptor7 source draft

Descriptor8 retains this rule unchanged and adds the separately selected exact24-name
absence rule; see `RETIRED-OPTIONS.md`. The consumed descriptor7 evidence below remains
historical component evidence, not descriptor8 compilation/runtime acceptance.

Operator selected **Require exact mirror** in `.pi/g1-identity-mirror-proposal.md`.
This is a native semantic-format rule, not account certification or whole-engine acceptance.
The guarded factory remains fenced; no coherent SDK/actual-engine/genuine-access approval.

## Rule and phases

* PMC my_id remains canonical decimal **1..(2^40 - 1)**, with no sign, leading zero,
  whitespace, legacy `user N`, binary/trailing content or overflow.
* Config my_id must be present and exactly **`I` + those same PMC decimal bytes**.
  B/I/S grammar alone is insufficient; another canonical integer is NOT a matching mirror.
* EXISTING quarantine checks the pair before Global/options/managers consume or overwrite it.
* NEW pristine/open/unsealed authentication does not require identity. Native self-user
  processing must produce the pair; seal capture AND post-sync guarded recheck require it.
* Missing/malformed/mismatch at an applicable pair-validation point is refusal, never repair,
  synthesis, get-me fallback, healthy Busy/Changed or implicit NEW.
* Descriptor7 pins this policy. Admission record sizes stay112/120; prior descriptors are
  not silently adopted/migrated. No identity/key hash is added to metadata.

## Implementation boundaries

`native-value-codec.h::canonical_personal_user_id` is allocation-free, bounds input before
arithmetic and shares the already-selected native UserId domain.
`personal_identity_pair_valid` borrows two bounded string views and returns only bool.

Private TdDb `identity_pair_valid` adapts raw quarantine stores or already-guarded snapshots.
It is called at EXISTING open and both seal-validation passes. Snapshot lookup never calls
ordinary owner getters or recursively locks; no slice escapes, callback, sync or wait occurs
under the pair views. Approved incarnation/revision/same-binlog-actor architecture is retained.

The initializer barrier is STILL required. Native production of the two fields is not an
atomic cross-map write, so do not arm admission from early authorization READY or this codec
alone. Runtime authority-loss handling belongs to B2 and is not implemented by a startup pair.

## Mapped PUBLIC leaf proof (opt-in)

The native-value fixture adds eight ordered families to the prior32:

1. native-personal-id-and-mirror-boundaries
2. native-personal-id-mirror-producer
3. identity-missing-and-empty-mirror-refused
4. identity-grammar-valid-is-not-pair-authority
5. identity-noncanonical-and-range-refused
6. identity-all-mirror-truncations-and-extra
7. identity-binary-and-non-ascii-refused
8. identity-borrowed-subviews-and-no-mutation

It uses the official pure UserId domain and native StringBuilder writer recipes, with
independent boundary/byte goldens. It does not construct UserManager, OptionManager, TdDb,
Td, Client, Global, AuthDataShared or an account/session actor. The value-only missing/empty
case is not execution proof of a database-open phase or actual authorization barrier.

The context suite separately exercises metadata/context primitives with authorization/sync
SIMULATED. Fresh full object/private-Java compilation can check the source instantiations,
not execute those guards. Receipts and acceptance status belong in coordinator-base.json
and `.pi/g1-native-work-handoff.md`; do not infer runtime acceptance from this mapping.

## Consumed descriptor7 component evidence

* PUBLIC **b813ff844**: 14 context cases +40 value families (eight new identity families),
  independently consumed in `.pi/g1-identity-mirror-public-verification.json`.
  545 protected/441 frozen hashes, exact outcomes, six unchanged trees, eight independent
  metadata records, headers, symbols and stopped/removed container absence matched.
* Compilation **b941cbe4a**: ten native objects +seven private Java classes /11 checks,
  `.pi/g1-identity-mirror-compile-verification.json`; 545 protected/437 frozen hashes and
  all logs/exact container absence consumed. Only three known redundant-move warnings.
* The three PUBLIC storage/context objects match the fresh full compilation. TdDb.o has
  the actual pair predicate plus raw-quarantine and borrowed-snapshot template symbols.
  `.pi/g1-identity-mirror-release-preflight.json` records these checks. This is template
  compilation, NOT execution of database-open or seal guards.
* Schema: `93f028b770ea8277bd8ed1f57423dfa42b00e10cd59347d88cffd289f196afb9`.
  Frozen evidence is preserved. Input hold released only after both parent consumptions;
  the release receipt accounts for post-release manifest/documentation changes.

These accept the SOURCE/LEAF component only. They do not prove required-initializer or
production pending coordination, actual TdDb/Td/Client execution, coherent SDK link,
activation, genuine account authority, privacy/stop/purge completeness or usable reopen.
The earlier descriptor6 24-PASS-prefix failure remains UNDETERMINED; later passes do not
explain it. No default JVM regression is claimed by these native-only runs.

Privacy/loss/purge/Close/lease integration, other option/family/replay semantics, production
wake coordination, required initializer completion and older failure disposition remain open.
All production timing/fairness/completion policies remain unselected.
