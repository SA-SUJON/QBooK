#!/usr/bin/env python3
"""Verify the blocklist asset is present and readable inside a built APK.

aapt strips the .gz suffix and applies its own compression, so the runtime
entry is named blocklist.txt and is already decompressed. Getting that wrong
silently disabled the entire network blocklist in v3.2.0, so CI checks the
real artifact rather than the source tree.
"""
import gzip
import sys
import zipfile

MUST_BLOCK = ("doubleclick.net", "babu88.com", "krikya.com", "jeetbuzz.com")
MUST_ALLOW = ("facebook.com", "messenger.com", "bkash.com", "fbcdn.net")
MIN_DOMAINS = 100_000


def main(path: str) -> int:
    z = zipfile.ZipFile(path)
    names = [n for n in z.namelist() if "blocklist" in n]
    if not names:
        print("::error::no blocklist asset in the APK")
        return 1

    name = names[0]
    raw = z.read(name)
    data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
    lines = {l.strip() for l in data.decode().split("\n") if l.strip()}
    print(f"asset {name}: {len(lines):,} domains")

    if len(lines) < MIN_DOMAINS:
        print(f"::error::only {len(lines)} domains, the list did not ship")
        return 1

    ok = True
    for d in MUST_BLOCK:
        if d not in lines:
            print(f"::error::{d} missing from the blocklist")
            ok = False
    for d in MUST_ALLOW:
        if d in lines:
            print(f"::error::{d} must never be blocked")
            ok = False

    if ok:
        print("blocklist verified inside the APK")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
