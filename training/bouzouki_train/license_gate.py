"""Data-license gate for fine-tuning.

finetune.py refuses to train unless ``training/DATA_LICENSES.yaml``:

1. has ``owner_approval.approved: true`` (a YAML boolean, set by the owner by hand);
2. lists every data source the run uses (the dataset metadata's source ids, the pretrained weights,
   the real validation recordings) with a filled-in ``license`` and ``approved: true``;
3. lists no non-commercial license (NC / non-commercial / research-only);

and the forbidden-source scan finds nothing. The scan looks, case- and punctuation-insensitively,
for the names below in the YAML source entries, in the file and directory names of every data /
model path of the run, in the repository's file names, and in the installed Python packages and
imported modules. These are non-commercial (CC BY-NC or similar) and must never be used:

* SynthTab      (synthetic guitar-tab dataset, CC BY-NC 4.0)
* MuScriptor    (model weights, non-commercial licence)
* gtrsnipe      (code, non-commercial licence)

The forbidden scan runs even in ``--dry-run``; only the approval check is skipped there.

    python -m bouzouki_train.license_gate --data data/tfrecords --packages
"""
import argparse
import glob
import json
import os
import re
import sys
from typing import Dict, Iterable, List, Optional

FORBIDDEN: Dict[str, str] = {
    "synthtab": "SynthTab dataset (CC BY-NC 4.0, non-commercial)",
    "muscriptor": "MuScriptor weights (non-commercial)",
    "gtrsnipe": "gtrsnipe code (non-commercial)",
}
NONCOMMERCIAL = re.compile(r"(\bnc\b|by-nc|non-?commercial|research[ -]only|personal[ -]use)", re.I)
PLACEHOLDER = re.compile(r"^\s*(|todo.*|fill.*|unknown|\?+|<.*>)\s*$", re.I)
#: Licenses accepted for Python packages by --packages (warning only; see README).
PACKAGE_LICENSE_OK = re.compile(r"(apache|mit\b|mit-0|bsd|mpl|mozilla|psf|python software|isc|unlicense|public domain)", re.I)
#: Packages whose metadata has no usable license field, checked by hand against their LICENSE file.
KNOWN_LICENSES = {
    "basic-pitch": "Apache-2.0",
    "pyroomacoustics": "MIT",
    "setuptools": "MIT",
}

SKIP_DIRS = {".git", ".gradle", "build", "node_modules", "__pycache__", ".venv", "venv", ".idea"}
HERE = os.path.dirname(os.path.abspath(__file__))
TRAINING_DIR = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(TRAINING_DIR)
DEFAULT_YAML = os.path.join(TRAINING_DIR, "DATA_LICENSES.yaml")


class LicenseGateError(RuntimeError):
    pass


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def forbidden_hits(text: str) -> List[str]:
    n = _norm(text)
    return [desc for key, desc in FORBIDDEN.items() if key in n]


def _walk_names(path: str, limit: int = 200_000) -> Iterable[str]:
    yield os.path.abspath(path)
    if os.path.isdir(path):
        count = 0
        for root, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for name in dirs + files:
                yield os.path.join(root, name)
                count += 1
                if count >= limit:
                    return


def scan_forbidden(paths: Iterable[str] = (), texts: Iterable[str] = (), environment: bool = True, repo: bool = True) -> List[str]:
    """Returns a list of problems (empty = clean)."""
    problems = []
    for t in texts:
        for d in forbidden_hits(str(t)):
            problems.append(f"{d}: named in {t!r}")
    roots = [p for p in paths if p]
    if repo:
        roots.append(REPO_ROOT)
    for root in roots:
        if not os.path.exists(root):
            continue
        for p in _walk_names(root):
            rel = os.path.basename(p) if root == REPO_ROOT else p
            for d in forbidden_hits(rel):
                problems.append(f"{d}: path {p}")
    if environment:
        try:
            import importlib.metadata as md

            for dist in md.distributions():
                name = dist.metadata.get("Name") or ""
                for d in forbidden_hits(name):
                    problems.append(f"{d}: installed Python package {name!r}")
        except Exception:  # pragma: no cover
            pass
        for mod in list(sys.modules):
            for d in forbidden_hits(mod):
                problems.append(f"{d}: imported module {mod!r}")
    return sorted(set(problems))


def load_yaml(path: str) -> dict:
    import yaml

    with open(path) as f:
        data = yaml.safe_load(f) or {}
    if not isinstance(data, dict):
        raise LicenseGateError(f"{path}: not a mapping")
    return data


def dataset_source_ids(tfrecord_base: str) -> Dict[str, int]:
    """Source ids from every ``metadata.jsonl`` under the TFRecord base directory, with counts."""
    ids: Dict[str, int] = {}
    for meta in glob.glob(os.path.join(tfrecord_base, "*", "metadata.jsonl")):
        with open(meta) as f:
            for line in f:
                if line.strip():
                    for s in json.loads(line).get("sources", []):
                        ids[s] = ids.get(s, 0) + 1
    return ids


def check(
    yaml_path: str = DEFAULT_YAML,
    used_ids: Iterable[str] = (),
    scan_paths: Iterable[str] = (),
    require_approval: bool = True,
) -> List[str]:
    """Raises LicenseGateError listing every problem; returns notes for the log when it passes."""
    problems: List[str] = []
    notes: List[str] = []
    data = load_yaml(yaml_path) if os.path.exists(yaml_path) else None
    if data is None:
        problems.append(f"{yaml_path} not found")
        data = {}
    sources = {str(s.get("id")): s for s in data.get("sources", []) or [] if isinstance(s, dict)}

    # Forbidden names: in the listed sources, in the run's paths, in the repo and the environment.
    texts = [str(v) for s in sources.values() for k, v in s.items() if k in ("id", "description", "path", "url", "license", "name")]
    problems += scan_forbidden(scan_paths, texts)

    for sid, s in sources.items():
        lic = str(s.get("license", "") or "")
        if NONCOMMERCIAL.search(lic):
            problems.append(f"source {sid!r}: non-commercial license {lic!r} is not allowed")

    if require_approval:
        approval = data.get("owner_approval", {}) or {}
        if approval.get("approved") is not True:
            problems.append("owner_approval.approved is not true: the owner must review DATA_LICENSES.yaml and set it")
        for sid in sorted(set(used_ids)):
            s = sources.get(sid)
            if s is None:
                problems.append(f"source {sid!r} is used but not listed in {os.path.basename(yaml_path)}")
                continue
            if PLACEHOLDER.match(str(s.get("license", "") or "")):
                problems.append(f"source {sid!r}: license not filled in")
            if s.get("approved") is not True:
                problems.append(f"source {sid!r}: approved is not true")
    else:
        notes.append("license approval NOT checked (dry run)")
    if problems:
        raise LicenseGateError("license gate failed:\n  - " + "\n  - ".join(problems))
    notes.append(f"license gate passed for sources: {sorted(set(used_ids))}" if require_approval else "forbidden-source scan clean")
    return notes


def package_report() -> List[str]:
    """Installed distributions whose declared license is not in PACKAGE_LICENSE_OK (warnings)."""
    import importlib.metadata as md

    out = []
    for dist in md.distributions():
        m = dist.metadata
        if (m.get("Name") or "").lower() in KNOWN_LICENSES:
            continue
        lic = " ".join(filter(None, [m.get("License-Expression"), (m.get("License") or "").split("\n")[0]]
                              + [c for c in (m.get_all("Classifier") or []) if c.startswith("License")]))
        if not PACKAGE_LICENSE_OK.search(lic):
            out.append(f"{m.get('Name')}=={m.get('Version')}: {lic or 'no license metadata'}")
    return sorted(out)


def main(argv: Optional[List[str]] = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--licenses", default=DEFAULT_YAML)
    ap.add_argument("--data", help="TFRecord base dir: check the sources in its metadata")
    ap.add_argument("--extra-source", action="append", default=[], help="another source id the run uses")
    ap.add_argument("--path", action="append", default=[], help="another path to scan")
    ap.add_argument("--packages", action="store_true", help="also report installed packages with other licenses")
    a = ap.parse_args(argv)
    used = list(a.extra_source) + (list(dataset_source_ids(a.data)) if a.data else [])
    try:
        for n in check(a.licenses, used, [a.data] + a.path if a.data else a.path):
            print(n)
        status = 0
    except LicenseGateError as e:
        print(e)
        status = 1
    if a.packages:
        rep = package_report()
        print("packages outside Apache/MIT/BSD/MPL/PSF/ISC:" if rep else "all installed packages are Apache/MIT/BSD/MPL/PSF/ISC")
        for r in rep:
            print("  ", r)
    sys.exit(status)


if __name__ == "__main__":
    main()
