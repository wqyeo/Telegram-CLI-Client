#!/usr/bin/env python3
"""Reserve a NEW package destination. Never overwrite, repair or remove output."""
import os
import stat
import sys
from pathlib import Path


def reserve(raw: str) -> Path:
    output = Path(raw)
    if (not output.is_absolute() or str(output) != raw or ".." in output.parts
            or any(ord(char) < 32 or 127 <= ord(char) <= 159 or char == os.pathsep for char in raw)
            or output == output.parent or os.path.lexists(output)):
        raise ValueError("invalid output")
    repository = Path(__file__).resolve().parents[2]
    forbidden = [repository / 'target']  # Maven clean would destroy the reservation.
    if os.path.lexists(repository / 'dist'):
        forbidden.append(repository / 'dist')  # Do not even add output under the old install.
    if any(output == root or root in output.parents for root in forbidden):
        raise ValueError("build/existing distribution output")
    # Resolve only existing parents; no symlink ancestors or recursively created paths.
    if output.parent.resolve(strict=True) != output.parent:
        raise ValueError("linked parent")
    for parent in (output.parent, *output.parent.parents):
        info = parent.lstat()
        root_sticky = info.st_uid == 0 and info.st_mode & stat.S_ISVTX
        if (not stat.S_ISDIR(info.st_mode) or info.st_uid not in (0, os.getuid())
                or (info.st_mode & 0o022 and not root_sticky)):
            raise ValueError("untrusted parent")
    output.mkdir(mode=0o700)  # Atomic reservation; an existing path fails, never removed.
    return output


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("invalid arguments")
        reserve(sys.argv[1])
    except (OSError, ValueError):
        print("Package output must be a NEW absolute normalized directory below a trusted existing parent; no output was removed. Use --output DIRECTORY.", file=sys.stderr)
        sys.exit(2)
