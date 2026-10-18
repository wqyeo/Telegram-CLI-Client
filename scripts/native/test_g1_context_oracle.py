"""Opt-in PUBLIC header-oracle unit tests; no container, native code, SDK or providers.

Run with python3 -m unittest discover -s scripts/native -p test_g1_context_oracle.py.
This oracle is ONLY for the header-only fixture, not ordinary account stores with rows.
"""
import hashlib
import hmac
import importlib.util
import struct
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('g1_context_oracle', ROOT / 'native/hardening/run-context.py')
assert SPEC is not None and SPEC.loader is not None
oracle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(oracle)


def public_header():
    salt, iv = b'S' * 32, b'I' * 16
    public_key = hashlib.pbkdf2_hmac('sha256', b'A' * 32, salt, 2, dklen=32)
    key_hash = hmac.digest(public_key, b'cucumbers everywhere', 'sha256')
    data = struct.pack('<IQiiQI', 128, 0, -3, 0, 0, 0)
    for value in (salt, iv, key_hash):
        data += bytes([len(value)]) + value + bytes((-(len(value) + 1)) % 4)
    return data + struct.pack('<I', zlib.crc32(data))


class PublicHeaderOracleTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='g1-public-header-oracle-')
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / 'PUBLIC-header'
        # Pure Python file checks must not acquire a native/client runtime capability.
        guard = patch.object(oracle.subprocess, 'run', side_effect=AssertionError('Native execution forbidden'))
        guard.start()
        self.addCleanup(guard.stop)
        self.data = public_header()

    def check(self, data):
        self.path.write_bytes(data)
        return oracle.public_header_checks(self.path)

    def test_independent_public_wire_is_exactly128_and_accepted(self):
        self.assertEqual(128, len(self.data))
        self.assertTrue(all(self.check(self.data).values()))

    def test_missing_empty_truncated_extra_and_old160_assumption_refuse(self):
        self.assertFalse(all(oracle.public_header_checks(self.path).values()))
        for data in (b'', self.data[:-1], self.data + b'X', self.data + bytes(32)):
            with self.subTest(length=len(data)):
                self.assertFalse(all(self.check(data).values()))

    def test_framing_flags_tl_padding_and_key_hash_refuse_even_with_recomputed_crc(self):
        changes = ((4, 'id'), (12, 'type'), (16, 'event-flags'), (20, 'extra'),
                   (28, 'service-flags'), (32, 'salt-length'), (65, 'salt-padding'),
                   (68, 'iv-length'), (85, 'iv-padding'), (88, 'hash-length'),
                   (121, 'hash-padding'), (33, 'salt-key-mismatch'), (89, 'key-hash'))
        for offset, label in changes:
            with self.subTest(field=label):
                data = bytearray(self.data)
                data[offset] ^= 1
                struct.pack_into('<I', data, 124, zlib.crc32(data[:124]))
                self.assertFalse(all(self.check(data).values()))

    def test_crc_failure_is_reported_independently(self):
        data = bytearray(self.data)
        data[-1] ^= 1
        self.assertFalse(self.check(data)['crc32'])

    def test_valid_other_iv_is_not_falsely_claimed_as_detectable_corruption(self):
        # The native key hash does not bind the IV. No universal-corruption claim.
        data = bytearray(self.data)
        data[69] ^= 1
        struct.pack_into('<I', data, 124, zlib.crc32(data[:124]))
        self.assertTrue(all(self.check(data).values()))


if __name__ == '__main__':
    unittest.main()
