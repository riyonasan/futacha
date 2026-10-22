"""Pinned public build inputs; generated files stay under build/."""
import hashlib
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def verified_download(url, filename, sha256):
    archive = ROOT / "build" / "native-downloads" / filename
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.is_file():
        partial = archive.with_suffix(archive.suffix + ".part")
        with urllib.request.urlopen(url, timeout=120) as response, partial.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        verify_hash(partial, sha256)
        partial.replace(archive)
    verify_hash(archive, sha256)
    return archive


def verify_hash(path, expected):
    with Path(path).open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"SHA-256 mismatch: {path}")


def extract_zip(archive, destination):
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            if not (destination / entry.filename).resolve().is_relative_to(destination):
                raise ValueError("Archive path escapes destination")
        source.extractall(destination)
