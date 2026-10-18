#!/usr/bin/env python3
"""PUBLIC bounded codecs used by real admission; no Client/TdDb/provider invocation."""
import hashlib
import json
import subprocess
from pathlib import Path

SRC, OUT = Path('/g1'), Path('/stage')
BASE = Path('/work/tdlib-a94b83d67ab4c8397c6245e541b31a4131ea2f79')
LABELS = ('native-authorized-primary', 'native-authorized-secondary', 'native-empty-secondary',
          'empty-primary-refused', 'native-optional-created-at', 'secondary-versus-primary-role',
          'all280-truncations-and-extra', 'all30-unsupported-flags', 'temporary-expiry-role-refused',
          'tl-framing-refused', 'all264-key-id-and-key-mismatches', 'all16-noncanonical-empty-keys',
          'created-at-invalid-casts', 'created-at-safe-negative-boundary', 'native-salts-including-expired',
          'native-empty-salt-vector', 'all76-salt-truncations-and-extra', 'salt-count-bounded-before-allocation',
          'salt-nonfinite-and-unsafe-casts', 'salt-reversed-interval', 'native-option-producer-grammar',
          'option-noncanonical-and-overflow', 'option-valid-utf8-and-control', 'option-invalid-utf8',
          'current-server-time-recipe-and-boundaries', 'server-time-all16-truncations-legacy8-and-extra',
          'server-time-nonfinite-and-outside-native-domain', 'config-expire-signed32-including-expired',
          'config-expire-noncanonical-and-overflow', 'numbered-dc-key-family-roles',
          'malformed-numbered-dc-family-refused', 'recovery-is-not-personal-primary-authority',
          'native-personal-id-and-mirror-boundaries', 'native-personal-id-mirror-producer',
          'identity-missing-and-empty-mirror-refused', 'identity-grammar-valid-is-not-pair-authority',
          'identity-noncanonical-and-range-refused', 'identity-all-mirror-truncations-and-extra',
          'identity-binary-and-non-ascii-refused', 'identity-borrowed-subviews-and-no-mutation',
          'retired-option-exact24-refused', 'retired-option-entry-values-refused',
          'retired-option-near-misses-and-current-entries', 'retired-option-name-framing-preserved',
          'retired-option-borrowed-subviews-and-no-mutation')


def main(flags):
    binary = OUT / 'public-value-checker'
    command = [*flags, '-Werror', '-I/stage/source/td/telegram/g1',
               str(SRC / 'native-value-public-fixture.cpp'), str(BASE / 'jnibuild/tdutils/libtdutils.a'),
               '-lcrypto', '-lz', '-lpthread', '-ldl', '-o', str(binary)]
    (OUT / 'value-command.json').write_text(json.dumps(command, indent=2) + '\n')
    compiled = subprocess.run(command, capture_output=True, timeout=90)
    (OUT / 'value-compile.log').write_bytes(compiled.stdout + compiled.stderr)
    result = {'scope': 'PUBLIC native serializers/bounded value codecs only; not account semantics or actor ordering',
              'compiled': compiled.returncode == 0, 'compileExitCode': compiled.returncode,
              'tdDbOrClientExecuted': False, 'allPassed': False}
    (OUT / 'value-result.json').write_text(json.dumps(result, indent=2) + '\n')
    if compiled.returncode != 0:
        return 1
    checked = subprocess.run([str(binary)], capture_output=True, timeout=15)
    (OUT / 'value-stdout').write_bytes(checked.stdout)
    (OUT / 'value-stderr').write_bytes(checked.stderr)
    expected = ''.join('PUBLIC_VALUE_CHECK ' + name + ' PASS\n' for name in LABELS)
    expected += 'PUBLIC_NATIVE_VALUES_ACCEPTED account_engine=0\n'
    passed = checked.returncode == 0 and checked.stdout == expected.encode() and checked.stderr == b''
    result.update(exitCode=checked.returncode, allPassed=passed, exactProcessOutcome=passed,
                  binarySha256=hashlib.sha256(binary.read_bytes()).hexdigest(), expectedFamilies=list(LABELS))
    (OUT / 'value-result.json').write_text(json.dumps(result, indent=2) + '\n')
    print('PUBLIC_NATIVE_VALUE_CODECS', 'PASS' if passed else 'FAIL', 'families=' + str(len(LABELS)), flush=True)
    return 0 if passed else 1
