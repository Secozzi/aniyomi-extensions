import gzip
import hashlib
import json
import sys
from pathlib import Path

import index_pb2
from github_utils import REPO_NAME, run_gh
from google.protobuf import json_format

# Artifacts downloaded from the build jobs: one APK per extension plus the source metadata JSON
# emitted by each assembleRelease.
ARTIFACTS_DIR = Path.home() / "apk-artifacts"

# The checked-out `repo` branch we publish into (the working directory).
REPO_DIR = Path.cwd()

ICON_BASE_URL = "https://cdn.jsdelivr.net/gh/secozzi/aniyomi-extensions@master"
RELEASE_BASE_URL = f"https://github.com/{REPO_NAME}/releases/download"

current_sha = sys.argv[1]
current_sha_short = current_sha[:7]

with REPO_DIR.joinpath("index.json").open() as f:
    remote_proto = json_format.Parse(f.read(), index_pb2.Index())

remote_extensions = {
    ext.packageName: ext for ext in remote_proto.extensionList.extensions
}

release_assets_path = REPO_DIR / "release-assets.json"
if release_assets_path.exists():
    with release_assets_path.open() as f:
        release_assets = json.load(f)
else:
    release_assets = {}

updated_release_assets = {
    package_name: assets
    for package_name, assets in release_assets.items()
}

# Build index entries for the built apks. Each extension's metadata comes from the
# source-info JSON emitted by its assembleRelease task (see GenerateSourceInfoTask); its APK is a
# sibling in the same build dir. aapt reads the icon out of the APK
extensions: list[tuple[index_pb2.Extension, Path, bool]] = []

SOURCE_DIR = Path(__file__).resolve().parents[2]
ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"


def get_icon_url(module: str) -> str:
    module_icon = f"src/{module.replace('.', '/')}/{ICON_FILE}"
    if (SOURCE_DIR / module_icon).exists():
        return f"{ICON_BASE_URL}/{module_icon}"

    return f"{ICON_BASE_URL}/core/src/main/{ICON_FILE}"


for info_file in ARTIFACTS_DIR.glob("**/source-info.json"):
    with info_file.open(encoding="utf-8") as f:
        info = json.load(f)
    package_name = info["packageName"]
    versionName = info["versionName"]
    apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
    if apk is None:
        raise FileNotFoundError(
            f"{package_name}: no release apk found under {info_file.parent}"
        )

    assets = {
        "apk": {
            "name": apk.name,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        },
    }
    old_assets = release_assets.get(package_name, {})
    apk_changed = (
        package_name not in remote_extensions
        or remote_extensions[package_name].versionName != versionName
    )

    updated_release_assets[package_name] = assets

    ext = index_pb2.Extension(
        name=info["name"],
        packageName=package_name,
        resources=index_pb2.Resources(
            iconUrl=get_icon_url(info["module"]),
        ),
        extensionLib=info["extensionLib"],
        versionCode=info["versionCode"],
        versionName=versionName,
        contentWarning=info["contentWarning"],
        isTorrent=info["isTorrent"],
        sources=[
            index_pb2.Source(
                id=int(source["id"]),
                name=source["name"],
                language=source["lang"],
                homeUrl=source["baseUrl"],
                mirrorUrls=source.get("mirrorUrls", []),
            )
            for source in info["sources"]
        ],
    )
    extensions.append((ext, apk, apk_changed))

extensions.sort(key=lambda item: item[0].packageName)
changed_extensions = [item for item in extensions if item[2]]


def get_release_tag() -> str:
    return current_sha_short

changed_index = 0
for ext, apk, apk_changed in extensions:
    if apk_changed:
        tag = get_release_tag()
        old_resources = remote_extensions.get(ext.packageName)
        ext.resources.apkUrl = (
            f"{RELEASE_BASE_URL}/{tag}/{apk.name}"
            if apk_changed
            else old_resources.resources.apkUrl
        )
        changed_index += 1
    else:
        old_resources = remote_extensions[ext.packageName].resources
        ext.resources.apkUrl = old_resources.apkUrl

# Merge with the already-published index, dropping the deleted/rebuilt modules.
final_extensions = []
final_extensions.extend(ext for ext, _, _ in extensions)
final_extensions.sort(key=lambda ext: ext.packageName)

index = index_pb2.Index(
    name="Jellyfin, Stremio, and Torbox",
    badgeLabel="SECO",
    signingKey="480e90b05421e4f92fb789af27760718796e57b87c1a1ad66c55fa1f0a82df3e",
    contact=index_pb2.Contact(
        website="https://github.com/Secozzi/aniyomi-extensions",
    ),
    extensionList=index_pb2.ExtensionList(extensions=final_extensions),
)

with REPO_DIR.joinpath("index.json").open("w", encoding="utf-8") as f:
    f.write(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        )
    )

with REPO_DIR.joinpath("index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

with release_assets_path.open("w", encoding="utf-8") as f:
    json.dump(updated_release_assets, f, indent=2, sort_keys=True)
    f.write("\n")

# --- Upload assets as release ---
if not changed_extensions:
    sys.exit(0)

def create_release(tag: str):
    if run_gh(
        "release",
        "view",
        tag,
        "--repo",
        REPO_NAME,
        "--json",
        "tagName",
        success_errors=("release not found",),
    ):
        print(f"Release {tag} already exists")
        return

    print(f"Creating release {tag}")
    run_gh(
        "release",
        "create",
        tag,
        "--repo",
        REPO_NAME,
        "--draft",
        "--title",
        f"Repository Update {tag}",
        "--notes",
        f"Automated update from Secozzi/aniyomi-extensions@{current_sha}",
    )


def publish_release(tag: str):
    print(f"Publishing release {tag}")
    run_gh("release", "edit", tag, "--repo", REPO_NAME, "--draft=false")

def get_release_assets(tag: str) -> dict[str, str]:
    release = json.loads(
        run_gh(
            "release",
            "view",
            tag,
            "--repo",
            REPO_NAME,
            "--json",
            "assets",
        )
    )
    return {
        asset["name"]: (asset.get("digest") or "").removeprefix("sha256:")
        for asset in release["assets"]
    }


def upload_assets(tag: str, files: list[Path]):
    if not files:
        return

    existing_assets = get_release_assets(tag)
    files_to_upload = [
        file
        for file in files
        if existing_assets.get(file.name)
        != hashlib.sha256(file.read_bytes()).hexdigest()
    ]
    skipped = len(files) - len(files_to_upload)
    print(f"Uploading {len(files_to_upload)} assets to {tag}, skipping {skipped}")

    for f in files_to_upload:
        run_gh(
            "release",
            "upload",
            tag,
            str(f),
            "--repo",
            REPO_NAME,
            "--clobber",
        )

    publish_release(tag)


def main() -> None:
    files_to_upload = [
        apk
        for _, apk, apk_changed in changed_extensions
        if apk_changed
    ]

    tag = get_release_tag()
    create_release(tag)
    upload_assets(tag, files_to_upload)


if __name__ == "__main__":
    main()
