"""Unit checks for the manual PTY driver's cursor-size negotiation."""
import pathlib
import runpy
import unittest

TerminalReplies = runpy.run_path(str(pathlib.Path(__file__).with_name('pty-smoke.py')))['TerminalReplies']


class TerminalRepliesTest(unittest.TestCase):
    def test_clamps_size_probe_and_restores_cursor(self):
        terminal = TerminalReplies(lambda: (30, 100))
        replies = terminal.feed(b'\x1b[2;3H\x1b[s\x1b[5001;5001H\x1b[6n\x1b[u\x1b[6n')
        self.assertEqual([b'\x1b[30;100R', b'\x1b[2;3R'], replies)
        self.assertEqual([(30, 100)], terminal.reported_sizes)

    def test_handles_fragmented_queries(self):
        terminal = TerminalReplies(lambda: (14, 48))
        self.assertEqual([], terminal.feed(b'\x1b[5001;5001'))
        self.assertEqual([], terminal.feed(b'H\x1b[6'))
        self.assertEqual([b'\x1b[14;48R'], terminal.feed(b'n'))

    def test_uses_current_dimensions(self):
        dimensions = [30, 100]
        terminal = TerminalReplies(lambda: tuple(dimensions))
        self.assertEqual([b'\x1b[30;100R'], terminal.feed(b'\x1b[5001;5001H\x1b[6n'))
        dimensions[:] = [14, 48]
        self.assertEqual([b'\x1b[14;48R'], terminal.feed(b'\x1b[5001;5001H\x1b[6n'))
        self.assertEqual([b'\x1b[8;14;48t'], terminal.feed(b'\x1b[18t'))


if __name__ == '__main__':
    unittest.main()
