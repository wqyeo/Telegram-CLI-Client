"""PUBLIC-only offline mocks for the opt-in native runner; no containers or SDK."""
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('g1_runner', Path(__file__).with_name('test-g1-sqlite.py'))
assert SPEC is not None and SPEC.loader is not None
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
CID = 'a' * 64


def result(code=0, output=''):
    return subprocess.CompletedProcess([], code, output, '')


def stopped():
    return result(output=json.dumps({'Running': False, 'Status': 'exited', 'ExitCode': 0}))


class ContainerBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='g1-public-runner-mock-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cidfile = self.root / 'container.cid'
        self.cidfile.write_text(CID)

    def test_writable_output_has_no_input_or_receipt_alias(self):
        frozen, output = self.root / 'inputs', self.root / 'output'
        command = runner.container_command(self.root / 'cache', frozen, output,
                                           self.cidfile, 'PUBLIC_IMAGE', 'g1')
        volumes = [command[i + 1] for i, word in enumerate(command) if word == '--volume']
        self.assertIn(str(frozen) + ':/g1:ro,z', volumes)
        writable = [v for v in volumes if ':rw' in v]
        self.assertEqual([str(output) + ':/stage:rw,z'], writable)
        for protected in (frozen, self.root / 'host.json', self.cidfile):
            self.assertFalse(protected.is_relative_to(output))
        self.assertNotIn('--rm', command)
        self.assertIn('--timeout=570', command)
        self.assertEqual(str(self.cidfile), command[command.index('--cidfile') + 1])

    def test_normal_exit_waits_and_inspects_before_removing(self):
        with patch.object(runner.subprocess, 'run', side_effect=[result(), stopped(), result()]) as run:
            evidence = runner.finish_container(self.cidfile, False)
        self.assertTrue(evidence['terminationConfirmed'])
        self.assertTrue(evidence['removed'])
        self.assertEqual(['wait', 'container', 'rm'], [c.args[0][1] for c in run.call_args_list])
        self.assertTrue(all(c.kwargs['timeout'] == 20 for c in run.call_args_list))

    def test_host_timeout_kills_exact_cid_then_waits_and_inspects(self):
        with patch.object(runner.subprocess, 'run', side_effect=[result(), result(), stopped(), result()]) as run:
            evidence = runner.finish_container(self.cidfile, True)
        self.assertTrue(evidence['terminationConfirmed'])
        self.assertTrue(evidence['removed'])
        self.assertEqual(['podman', 'kill', '--signal', 'KILL', CID], run.call_args_list[0].args[0])
        self.assertEqual(['kill', 'wait', 'container', 'rm'], [c.args[0][1] for c in run.call_args_list])

    def test_already_exited_kill_error_requires_successful_wait_and_inspection(self):
        with patch.object(runner.subprocess, 'run', side_effect=[result(125), result(), stopped(), result()]):
            evidence = runner.finish_container(self.cidfile, True)
        self.assertEqual(125, evidence['killExitCode'])
        self.assertTrue(evidence['terminationConfirmed'])

    def test_wait_failure_does_not_remove_or_confirm(self):
        with patch.object(runner.subprocess, 'run', return_value=result(125)) as run:
            evidence = runner.finish_container(self.cidfile, False)
        self.assertFalse(evidence['terminationConfirmed'])
        self.assertFalse(evidence['removed'])
        self.assertEqual(1, run.call_count)

    def test_running_or_invalid_state_does_not_confirm_or_remove(self):
        outputs = [json.dumps({'Running': True, 'Status': 'running'}),
                   json.dumps({'Running': False, 'Status': 'created'}), '[]', '{invalid']
        for output in outputs:
            with self.subTest(output=output), patch.object(runner.subprocess, 'run',
                    side_effect=[result(), result(output=output)]) as run:
                evidence = runner.finish_container(self.cidfile, False)
                self.assertFalse(evidence['terminationConfirmed'])
                self.assertFalse(evidence['removed'])
                self.assertEqual(2, run.call_count)

    def test_cleanup_failure_retains_confirmed_termination_but_fails_cleanup(self):
        with patch.object(runner.subprocess, 'run', side_effect=[result(), stopped(), result(125)]):
            evidence = runner.finish_container(self.cidfile, False)
        self.assertTrue(evidence['terminationConfirmed'])
        self.assertFalse(evidence['removed'])

    def test_control_timeout_stops_without_retry_or_positive_claim(self):
        with patch.object(runner.subprocess, 'run', side_effect=subprocess.TimeoutExpired('PUBLIC', 20)) as run:
            evidence = runner.finish_container(self.cidfile, True)
        self.assertFalse(evidence['terminationConfirmed'])
        self.assertEqual(1, run.call_count)

    def test_missing_malformed_or_oversize_cid_never_runs_control_commands(self):
        for cid in ('', '../unrelated', 'A' * 64, 'b' * 81):
            with self.subTest(cid=cid), patch.object(runner.subprocess, 'run') as run:
                self.cidfile.write_text(cid)
                self.assertFalse(runner.finish_container(self.cidfile, True)['terminationConfirmed'])
                run.assert_not_called()
        self.cidfile.unlink()
        with patch.object(runner.subprocess, 'run') as run:
            self.assertFalse(runner.finish_container(self.cidfile, True)['terminationConfirmed'])
            run.assert_not_called()

    def test_no_post_run_hashes_when_termination_unconfirmed(self):
        state = {'containerLifecycle': {'terminationConfirmed': False},
                 'originalInputsUnchanged': False, 'frozenInputsUnchanged': False}
        with patch.object(runner, 'digest') as digest:
            runner.finalize_preservation(state, {}, self.root)
            digest.assert_not_called()
        self.assertFalse(state['preservationFinalized'])
        self.assertFalse(state['originalInputsUnchanged'])
        self.assertEqual('container-termination-unconfirmed-no-retry', state['status'])

    def test_confirmed_termination_allows_actual_hash_comparison(self):
        frozen = self.root / 'inputs'
        frozen.mkdir()
        payload = frozen / 'fixture'
        payload.write_bytes(b'PUBLIC')
        hashes = {'fixture': runner.digest(payload)}
        state = {'containerLifecycle': {'terminationConfirmed': True}, 'inputHashes': hashes}
        runner.finalize_preservation(state, {str(payload): hashes['fixture']}, frozen)
        self.assertTrue(state['preservationFinalized'])
        self.assertTrue(state['originalInputsUnchanged'])
        self.assertTrue(state['frozenInputsUnchanged'])
        payload.write_bytes(b'PUBLIC_CHANGED')
        runner.finalize_preservation(state, {str(payload): hashes['fixture']}, frozen)
        self.assertTrue(state['preservationFinalized'])
        self.assertFalse(state['originalInputsUnchanged'])
        self.assertFalse(state['frozenInputsUnchanged'])


if __name__ == '__main__':
    unittest.main()
