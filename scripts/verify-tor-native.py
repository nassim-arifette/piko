#!/usr/bin/env python3
"""Check the generated ELF libraries, JNI exports and Android-only dependencies."""

import hashlib
import re
import struct
import subprocess
import sys
from pathlib import Path

MACHINES = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86": 3, "x86_64": 62}
SYSTEM_LIBRARIES = {"libc.so", "libdl.so", "liblog.so", "libm.so"}
JNI_PREFIX = "Java_org_torproject_onionmasq_OnionMasqJni_"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def verify(path: Path, abi: str, readelf: Path) -> str:
    data = path.read_bytes()
    require(data[:4] == b"\x7fELF" and len(data) >= 64 and data[5] == 1, f"Not little-endian ELF: {path}")
    require(struct.unpack_from("<H", data, 18)[0] == MACHINES[abi], f"Wrong machine: {path}")
    elf64 = data[4] == 2
    offset = struct.unpack_from("<Q" if elf64 else "<I", data, 32 if elf64 else 28)[0]
    size, count = struct.unpack_from("<HH", data, 54 if elf64 else 42)
    loads = 0
    for index in range(count):
        header = offset + index * size
        kind = struct.unpack_from("<I", data, header)[0]
        flags = struct.unpack_from("<I", data, header + (4 if elf64 else 24))[0]
        if kind == 1:
            alignment = struct.unpack_from("<Q" if elf64 else "<I", data, header + (48 if elf64 else 28))[0]
            require(alignment >= 16384, f"Not 16 KiB aligned: {path}")
            loads += 1
        if kind == 0x6474E551:
            require(not flags & 1, f"Executable stack: {path}")
    require(loads > 0, f"No load segments: {path}")
    dynamic = subprocess.check_output([readelf, "--dynamic", path], text=True)
    needed = set(re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic))
    require(needed <= SYSTEM_LIBRARIES, f"Unbundled dependencies: {needed - SYSTEM_LIBRARIES}")
    symbols = subprocess.check_output([readelf, "--dyn-syms", "--wide", path], text=True)
    for method in ("init", "runProxy", "closeProxy", "isRunning", "refreshCircuits"):
        require(JNI_PREFIX + method in symbols, f"Missing JNI entrypoint: {method}")
    return hashlib.sha256(data).hexdigest()


if __name__ == "__main__":
    directory, ndk = (Path(argument).resolve() for argument in sys.argv[1:3])
    abis = sys.argv[3:] or list(MACHINES)
    readelf = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    checksums = []
    for abi in abis:
        name = f"{abi}/libonionmasq_mobile.so"
        checksums.append(f"{verify(directory / name, abi, readelf)}  {name}")
        print(f"{abi}: machine, 16 KiB load alignment, stack, dependencies and JNI exports OK")
    (directory / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
