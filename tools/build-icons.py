#!/usr/bin/env python3
"""Generate the project's original geometric Windows packaging icon."""
import struct
import zlib
from pathlib import Path


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


def main():
    size = 256
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            radius = ((x - 127.5) ** 2 + (y - 127.5) ** 2) ** 0.5
            cross = (abs(x - 127.5) < 5 and 36 < y < 220) or (abs(y - 127.5) < 5 and 36 < x < 220)
            target = 70 < radius < 80 or radius < 20 or cross
            raw.extend((112, 225, 197, 255) if target else (20, 31, 34, 255))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    directory = Path(__file__).resolve().parent.parent / "desktop/src-tauri/icons"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "icon.png").write_bytes(png)
    header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack("<BBBBHHII", 0, 0, 0, 0, 1, 32, len(png), 22)
    (directory / "icon.ico").write_bytes(header + entry + png)


if __name__ == "__main__":
    main()
