import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

module_path = Path(__file__).resolve().parents[1] / "native_dependencies.py"
spec = importlib.util.spec_from_file_location("native_dependencies", module_path)
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class NativeDependencyTest(unittest.TestCase):
    def test_corrupt_cached_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "archive"
            path.write_bytes(b"modified")
            with self.assertRaises(ValueError):
                native.verify_hash(path, hashlib.sha256(b"original").hexdigest())

    def test_archive_cannot_write_outside_build_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "input.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("../outside.txt", "unexpected")
            with self.assertRaises(ValueError):
                native.extract_zip(archive, root / "build")
            self.assertFalse((root / "outside.txt").exists())


if __name__ == "__main__":
    unittest.main()
