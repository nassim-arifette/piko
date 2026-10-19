#!/usr/bin/env python3
"""Bundle local Cargo package notices; report unresolved copyright texts explicitly."""

import json
import hashlib
import re
import shutil
import subprocess
import sys
from pathlib import Path


def generate(metadata_file: Path, root: Path, output: Path) -> None:
    inputs = sorted(metadata_file.glob("*.json")) if metadata_file.is_dir() else [metadata_file]
    documents = [json.loads(path.read_text()) for path in inputs]
    metadata = documents[0]
    metadata["packages"] = list({package["id"]: package for document in documents for package in document["packages"]}.values())
    nodes = {}
    for document in documents:
        for node in document["resolve"]["nodes"]:
            nodes.setdefault(node["id"], {"deps": []})["deps"].extend(node["deps"])
    pending = [package["id"] for package in metadata["packages"] if package["name"] == "onionmasq-mobile"]
    included = set()
    while pending:
        identifier = pending.pop()
        if identifier in included:
            continue
        included.add(identifier)
        for dependency in nodes[identifier]["deps"]:
            if any(kind["kind"] != "dev" for kind in dependency["dep_kinds"]):
                pending.append(dependency["pkg"])
    output.mkdir(parents=True, exist_ok=True)
    notices = []
    unresolved = []
    supplements_dir = root / "companion/native/licenses"
    supplements = json.loads((supplements_dir / "sources.json").read_text())
    for package in sorted(metadata["packages"], key=lambda item: (item["name"], item["version"])):
        if package["id"] not in included:
            continue
        directory = Path(package["manifest_path"]).parent
        candidates = set()
        for path in directory.iterdir():
            if path.is_file() and path.name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "COPYRIGHT", "NOTICE")):
                candidates.add(path)
            if path.is_dir() and path.name.upper() in ("LICENSES", "LICENCES"):
                candidates.update(item for item in path.rglob("*") if item.is_file())
        for identifier in re.findall(r"[A-Za-z0-9.+-]+", package.get("license") or ""):
            for suffix in ("", ".txt", ".md"):
                path = directory / (identifier + suffix)
                if path.is_file():
                    candidates.add(path)
        if package.get("license_file"):
            candidates.add(directory / package["license_file"])
        if not candidates and package["source"] is None:
            candidates.update(Path(metadata["workspace_root"]).glob("LICENSE*"))
        texts = []
        for path in sorted(candidates):
            if path.is_file():
                texts.append(f"--- {path.name} ---\n{path.read_text(errors='replace')}")
        sources = supplements.get(f"{package['name']}@{package['version']}", [])
        for source in sources:
            path = supplements_dir / source["file"]
            if hashlib.sha256(path.read_bytes()).hexdigest() != source["sha256"]:
                raise ValueError(f"Changed license supplement: {path.name}")
            texts.append(f"--- {source['url']} ---\n{path.read_text()}")
        identifier = f"{package['name']} {package['version']}"
        if not texts:
            unresolved.append(identifier)
        notices.append({"name": package["name"], "version": package["version"],
                        "license": package.get("license"), "authors": package.get("authors"),
                        "repository": package.get("repository"), "supplements": sources,
                        "texts": texts})
    shutil.copyfile(root / "LICENSE", output / "PIKO-LICENSE.txt")
    shutil.copyfile(root / "NOTICE", output / "PIKO-NOTICE.txt")
    sysroot = Path(subprocess.check_output(["rustc", "+1.97.1", "--print", "sysroot"], text=True).strip())
    rust_docs = sysroot / "share/doc/rust"
    shutil.copytree(rust_docs / "licenses", output / "rust-toolchain", dirs_exist_ok=True)
    shutil.copyfile(rust_docs / "COPYRIGHT-library.html", output / "rust-library-COPYRIGHT.html")
    (output / "rust-dependencies.json").write_text(json.dumps(notices, indent=2) + "\n")
    (output / "unresolved-notices.json").write_text(json.dumps(unresolved, indent=2) + "\n")
    print(f"Inventoried {len(notices)} packages; {len(unresolved)} without bundled notice text.")
    if unresolved:
        raise ValueError("Manual notice review required: " + ", ".join(unresolved))


if __name__ == "__main__":
    generate(*(Path(argument).resolve() for argument in sys.argv[1:]))
