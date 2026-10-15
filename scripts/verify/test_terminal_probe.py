"""Offline checks for the opt-in experimental dependency runner; never downloads."""
import hashlib
import json
import pathlib
import runpy
import tempfile
import unittest
from unittest import mock

PROBE = runpy.run_path(str(pathlib.Path(__file__).resolve().parents[1] / 'terminal-probe/probe.py'))


class TerminalProbeRunnerTest(unittest.TestCase):
    def test_lock_is_exactly_three_opt_in_libraries_with_sources_poms_and_license(self):
        lock = PROBE['read_lock']()
        self.assertEqual('4.4.8', lock['version'])
        self.assertEqual('BSD-3-Clause', lock['license'])
        self.assertEqual(10, len(lock['files']))
        self.assertTrue(all(entry['size'] < 1_000_000 for entry in lock['files']))

    def test_invalid_json_has_a_clear_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'lock.json'
            path.write_text('{broken', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Cannot read'):
                PROBE['read_lock'](path)

    def test_extra_path_or_duplicate_inventory_is_rejected(self):
        for mutate in (lambda files: files.append(dict(files[0])),
                       lambda files: files[0].update(name='../outside.jar')):
            with self.subTest(mutate=mutate), tempfile.TemporaryDirectory() as directory:
                lock = PROBE['read_lock']()
                mutate(lock['files'])
                path = pathlib.Path(directory) / 'lock.json'
                path.write_text(json.dumps(lock), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'inventory'):
                    PROBE['read_lock'](path)

    def test_content_size_and_hash_are_both_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'fixture.jar'
            entry = {'name': path.name, 'size': 4, 'sha256': hashlib.sha256(b'good').hexdigest()}
            path.write_bytes(b'good')
            PROBE['verify_file'](path, entry)
            path.write_bytes(b'evil')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                PROBE['verify_file'](path, entry)
            path.write_bytes(b'longer')
            with self.assertRaisesRegex(ValueError, 'wrong size'):
                PROBE['verify_file'](path, entry)

    def test_symlink_cache_entries_are_rejected_even_with_matching_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            real = pathlib.Path(directory) / 'real.jar'
            link = pathlib.Path(directory) / 'link.jar'
            real.write_bytes(b'good')
            link.symlink_to(real)
            with self.assertRaisesRegex(ValueError, 'linked'):
                PROBE['verify_file'](link, {'name': link.name, 'size': 4,
                    'sha256': hashlib.sha256(b'good').hexdigest()})

    def test_only_fixed_https_sources_are_derived_from_the_lock(self):
        lock = PROBE['read_lock']()
        for entry in lock['files']:
            url = PROBE['artifact_url'](lock, entry)
            if entry['name'] == 'JLine-LICENSE.txt':
                self.assertTrue(url.startswith('https://raw.githubusercontent.com/jline/jline3/'))
                self.assertIn(lock['sourceRevision'], url)
            else:
                self.assertTrue(url.startswith('https://repo.maven.apache.org/maven2/org/jline/'))
                self.assertTrue(url.endswith('/' + entry['name']))

    def test_prepare_does_not_overwrite_a_corrupt_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            lock = PROBE['read_lock']()
            path = root / lock['files'][0]['name']
            path.write_bytes(b'corrupt')
            with mock.patch('http.client.HTTPSConnection') as connection:
                with self.assertRaises(ValueError):
                    PROBE['prepare'](lock, root)
                connection.assert_not_called()
            self.assertEqual(b'corrupt', path.read_bytes())


if __name__ == '__main__':
    unittest.main()
