"""Prepare the existing Android VOICEVOX bridge's pinned C API and runtimes."""
import argparse
from pathlib import Path
import tarfile
import zipfile
from native_dependencies import ROOT, verified_download

CORE_VERSION = "0.17.0"
# CORE 0.17.0 recommends 1.23.2; the older Android 1.17.3 binary is only 4 KB aligned.
RUNTIME_VERSION = "1.23.2"
ASSETS = (
    ("arm64-v8a", "arm64", "arm64", "a2a7191bc2d9182d2d5c9cfa37fdf07f51d46219974da91786ff676bc1b0a54a", "43e6fc2a89ea6412d4cc20215042a3b34da22fdf05c1bb76256a686f82378d46"),
    ("x86_64", "x86_64", "x64", "c438436ac1d9f7767b40d83718e37d900688f77da3b370db39bc6fb573c57a61", "807e2ea50dfb2a309416adb1cee6edc53d3d8eae1d702ad4e6f199a0f91f2d24"),
)


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--android", action="store_true", required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    args = parser.parse_args()
    if not (args.ndk / "source.properties").is_file():
        parser.error("The configured Android NDK is missing")
    output = ROOT / "build/voicevox"
    header = None
    for abi, core_arch, runtime_arch, core_hash, runtime_hash in ASSETS:
        core_name = f"voicevox_core-android-{core_arch}-{CORE_VERSION}"
        core = verified_download(f"https://github.com/VOICEVOX/voicevox_core/releases/download/{CORE_VERSION}/{core_name}.zip", core_name + ".zip", core_hash)
        destination = output / "android/jniLibs" / abi
        with zipfile.ZipFile(core) as source:
            current_header = source.read(f"{core_name}/include/voicevox_core.h")
            if header is not None and header != current_header:
                raise ValueError("VOICEVOX headers differ between ABIs")
            header = current_header
            write(destination / "libvoicevox_core.so", source.read(f"{core_name}/lib/libvoicevox_core.so"))
            write(output / "licenses/voicevox-core-LICENSE.txt", source.read(f"{core_name}/LICENSE"))
        runtime_name = f"voicevox_onnxruntime-android-{runtime_arch}-{RUNTIME_VERSION}"
        runtime = verified_download(f"https://github.com/VOICEVOX/onnxruntime-builder/releases/download/voicevox_onnxruntime-{RUNTIME_VERSION}/{runtime_name}.tgz", runtime_name + ".tgz", runtime_hash)
        with tarfile.open(runtime) as source:
            for member_name, target in (
                ("lib/libvoicevox_onnxruntime.so", destination / "libvoicevox_onnxruntime.so"),
                ("TERMS.txt", output / "licenses/voicevox-runtime-TERMS.txt"),
                ("third-party-notices.html", output / "licenses/voicevox-runtime-ThirdPartyNotices.html"),
            ):
                member = source.getmember(f"{runtime_name}/{member_name}")
                if not member.isfile():
                    raise ValueError("Expected a regular file in VOICEVOX archive")
                with source.extractfile(member) as stream:
                    write(target, stream.read())
    write(output / "include/voicevox_core.h", header)
    print(f"VOICEVOX CORE {CORE_VERSION}, runtime {RUNTIME_VERSION} ready")


if __name__ == "__main__":
    main()
