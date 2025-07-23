import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

PACKAGE_NAME_REGEX = re.compile(r"package: name='([^']+)'")
VERSION_CODE_REGEX = re.compile(r"versionCode='([^']+)'")
VERSION_NAME_REGEX = re.compile(r"versionName='([^']+)'")
IS_NSFW_REGEX = re.compile(r"'tachiyomi.animeextension.nsfw' value='([^']+)'")
VERSION_ID_REGEX = re.compile(r"'tachiyomi.animeextension.versionId' value='([^']+)'")
NAMES_REGEX = re.compile(r"'tachiyomi.animeextension.names' value='([^']+)'")
APPLICATION_LABEL_REGEX = re.compile(r"^application-label:'([^']+)'", re.MULTILINE)
APPLICATION_ICON_320_REGEX = re.compile(
    r"^application-icon-320:'([^']+)'", re.MULTILINE
)
LANGUAGE_REGEX = re.compile(r"aniyomi-([^\.]+)")

*_, ANDROID_BUILD_TOOLS = (Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir()
REPO_DIR = Path("repo")
REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"

REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

index_data = []
index_min_data = []

def get_id(name: str, version_id: int) -> str:
    key = f"{name.lower()}/all/{version_id}"
    md5_hash = hashlib.md5(key.encode()).digest()
    result = 0
    for i in range(8):
        result |= (md5_hash[i] & 0xff) << (8 * (7 - i))

    return str(result & 0x7FFFFFFFFFFFFFFF)

for apk in REPO_APK_DIR.iterdir():
    badging = subprocess.check_output(
        [
            ANDROID_BUILD_TOOLS / "aapt",
            "dump",
            "--include-meta-data",
            "badging",
            apk,
        ]
    ).decode()

    package_info = next(x for x in badging.splitlines() if x.startswith("package: "))
    package_name = PACKAGE_NAME_REGEX.search(package_info).group(1)    
    application_icon = APPLICATION_ICON_320_REGEX.search(badging).group(1)

    with ZipFile(apk) as z, z.open(application_icon) as i, (
        REPO_ICON_DIR / f"{package_name}.png"
    ).open("wb") as f:
        f.write(i.read())

    language = LANGUAGE_REGEX.search(apk.name).group(1)
    names = NAMES_REGEX.search(badging).group(1).split(";")
    version_id = int(VERSION_ID_REGEX.search(badging).group(1))

    common_data = {
        "name": APPLICATION_LABEL_REGEX.search(badging).group(1),
        "pkg": package_name,
        "apk": apk.name,
        "lang": language,
        "code": int(VERSION_CODE_REGEX.search(package_info).group(1)),
        "version": VERSION_NAME_REGEX.search(package_info).group(1),
        "nsfw": int(IS_NSFW_REGEX.search(badging).group(1)),
    }
    min_data = {
        **common_data,
        "sources": [],
    }

    for i, name in enumerate(names):
        # TODO: Remove check with jellyfin versionId bump
        extName = name
        if i == 0 and "Jellyfin" in name:
            extName = "Jellyfin"

        min_data["sources"].append(
            {
                "name": name,
                "lang": language,
                "id": get_id(extName, version_id),
                "baseUrl": "",
                "versionId": version_id,
            }
        )

    index_min_data.append(min_data)
    index_data.append(
        {
            **common_data,
            "hasReadme": 0,
            "hasChangelog": 0,
            "sources": min_data["sources"],
        }
    )

index_data.sort(key=lambda x: x["pkg"])
index_min_data.sort(key=lambda x: x["pkg"])

with (REPO_DIR / "index.json").open("w", encoding="utf-8") as f:
    index_data_str = json.dumps(index_data, ensure_ascii=False, indent=2)
    print(index_data_str)
    f.write(index_data_str)

with (REPO_DIR / "index.min.json").open("w", encoding="utf-8") as f:
    json.dump(index_min_data, f, ensure_ascii=False, separators=(",", ":"))
