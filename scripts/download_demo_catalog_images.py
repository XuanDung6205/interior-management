"""Download/copy the 50 previously selected licensed catalog illustrations.

Images are committed so CI and the shop never need runtime access to providers.
Re-run only when replacing missing assets; existing images are left untouched.
"""

import hashlib
from pathlib import Path
import shutil
import urllib.request

from demo_catalog_images import PHOTOS, filename, source_url

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "frontend/public/images/catalog"


def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    seen = set()
    for family, photos in enumerate(PHOTOS, start=1):
        for style, photo in enumerate(photos, start=1):
            target = DESTINATION / filename(family, style)
            provider, identifier, _ = photo
            if not target.exists():
                if provider == "project":
                    shutil.copyfile(ROOT / "frontend/public/images" / identifier, target)
                else:
                    request = urllib.request.Request(source_url(photo), headers={"User-Agent": "NhaFurnitureDemo/1.0 (student project)"})
                    with urllib.request.urlopen(request, timeout=30) as response:
                        data = response.read()
                    if not data.startswith(b"\xff\xd8") or len(data) < 10000:
                        raise RuntimeError(f"Invalid image for {target.name}")
                    target.write_bytes(data)
            data = target.read_bytes()
            if not data.startswith(b"\xff\xd8"):
                raise RuntimeError(f"Not JPEG: {target.name}")
            digest = hashlib.sha256(data).hexdigest()
            if digest in seen:
                raise RuntimeError(f"Duplicate photo: {target.name}")
            seen.add(digest)
            print(target.name, provider, len(data), flush=True)
    print("Verified 50 distinct JPEGs.")


if __name__ == "__main__":
    main()
