"""Restore the Android OpenCV preparation entry point referenced by Gradle."""
import shutil
from native_dependencies import ROOT, extract_zip, verified_download

COMMIT = "40738fb16ceddb5fb3fea747585f7ce6abb0605b"
SHA256 = "343d496949d2114a182259a5ead09c0840b160356ee3c26b492c0fb454763245"


def main():
    output = ROOT / "build" / "android-tracking"
    source = output / "opencv"
    marker = source / ".futacha-source-sha256"
    if not marker.is_file() or marker.read_text().strip() != SHA256:
        archive = verified_download(f"https://codeload.github.com/opencv/opencv/zip/{COMMIT}", f"opencv-{COMMIT}.zip", SHA256)
        extracted = output / "extracted"
        extract_zip(archive, extracted)
        source.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(extracted / f"opencv-{COMMIT}", source, dirs_exist_ok=True)
        marker.write_text(SHA256 + "\n")
    licenses = output / "licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "shared/src/commonMain/composeResources/files/licenses/opencv-NOTICES.txt", licenses / "opencv-NOTICES.txt")
    print(f"OpenCV {COMMIT} ready")


if __name__ == "__main__":
    main()
