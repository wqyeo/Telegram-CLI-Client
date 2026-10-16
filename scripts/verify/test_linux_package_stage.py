"""Account-free output reservation tests; no builds, downloads or existing dist writes."""
import os
import runpy
import stat
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

RESERVE = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'package/linux-stage.py'))['reserve']


class LinuxPackageStageTest(unittest.TestCase):
    def test_new_unicode_output_is_private_and_existing_output_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / 'Telegram CLI 测试'
            self.assertEqual(output, RESERVE(str(output)))
            self.assertEqual(0o700, stat.S_IMODE(output.stat().st_mode))
            marker = output / 'existing-image'
            marker.write_bytes(b'public synthetic marker')
            with self.assertRaises(ValueError):
                RESERVE(str(output))
            self.assertEqual(b'public synthetic marker', marker.read_bytes())

    def test_existing_files_and_broken_or_valid_links_are_never_followed(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            file = root / 'archive.tar.gz'
            file.write_bytes(b'unchanged')
            broken = root / 'broken'
            broken.symlink_to(root / 'absent')
            link = root / 'link'
            link.symlink_to(file)
            for entry in (file, broken, link):
                with self.subTest(entry=entry.name), self.assertRaises(ValueError):
                    RESERVE(str(entry))
            self.assertEqual(b'unchanged', file.read_bytes())
            self.assertFalse((root / 'absent').exists())

    def test_symlink_ancestors_and_missing_parents_are_refused(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / 'real').mkdir()
            (root / 'alias').symlink_to(root / 'real', target_is_directory=True)
            with self.assertRaises(ValueError):
                RESERVE(str(root / 'alias' / 'stage'))
            with self.assertRaises(OSError):
                RESERVE(str(root / 'missing' / 'stage'))
            self.assertFalse((root / 'real' / 'stage').exists())
            self.assertFalse((root / 'missing').exists())

    def test_relative_root_noncanonical_and_classpath_separator_paths_are_refused(self):
        with tempfile.TemporaryDirectory() as root:
            for value in ('relative', '/', root + '/./stage', root + '/../stage',
                          root + '/stage/', root + '/bad:name',
                          *(root + '/bad' + chr(code) + 'name' for code in (*range(32), *range(127, 160)))):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    RESERVE(value)

    def test_writable_parent_is_not_trusted(self):
        with tempfile.TemporaryDirectory() as root:
            unsafe = Path(root) / 'unsafe'
            unsafe.mkdir(mode=0o700)
            unsafe.chmod(0o777)
            try:
                with self.assertRaises(ValueError):
                    RESERVE(str(unsafe / 'stage'))
                self.assertFalse((unsafe / 'stage').exists())
            finally:
                unsafe.chmod(0o700)

    def test_maven_output_and_existing_distribution_descendants_are_refused(self):
        repository = Path(__file__).resolve().parents[2]
        protected = [repository / 'target']
        if (repository / 'dist').exists():
            protected.append(repository / 'dist')
        for root in protected:
            with self.subTest(root=root.name), self.assertRaises(ValueError):
                RESERVE(str(root / 'synthetic-must-never-create'))
            self.assertFalse((root / 'synthetic-must-never-create').exists())

    def test_wrong_owner_policy_branch_is_refused_without_host_chown(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / 'stage'
            info = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_uid=os.getuid() + 10000)
            with mock.patch.object(Path, 'lstat', return_value=info), self.assertRaises(ValueError):
                RESERVE(str(output))
            self.assertFalse(output.exists())
