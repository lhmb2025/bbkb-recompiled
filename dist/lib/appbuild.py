"""Reading the app's version out of Gradle, and out of a built APK.

`app/build.gradle` is the single source of truth for the version; the APK is
checked AGAINST it. Publishing an APK whose versionCode does not match the
gradle file is how a release ends up advertising an update that the device then
refuses to install (or, worse, installs and then never updates again).
"""

import os
import re
import subprocess
import zipfile

VERSION_CODE = re.compile(r"^\s*versionCode\s*=?\s*(\d+)", re.MULTILINE)
VERSION_NAME = re.compile(r"^\s*versionName\s*=?\s*[\"']([^\"']+)[\"']", re.MULTILINE)
MIN_SDK = re.compile(r"^\s*minSdk\s*=?\s*(\d+)", re.MULTILINE)


class GradleVersion(object):
    def __init__(self, version_code, version_name, min_sdk, suffixes):
        self.version_code = version_code
        self.version_name = version_name          # base, no build-type suffix
        self.min_sdk = min_sdk
        self.suffixes = suffixes                  # build type -> versionNameSuffix

    def version_name_for(self, build_type):
        return self.version_name + self.suffixes.get(build_type, "")

    def tag(self):
        """The release tag: `v<base versionName>`, shared by all build types."""
        return "v" + self.version_name

    def asset_name(self, build_type):
        return "bbkb-%s-%s.apk" % (self.version_name, build_type)


def _build_type_suffix(text, build_type):
    """`versionNameSuffix` inside `buildTypes { <build_type> { ... } }`.

    Brace-counted rather than regexed across the block: the debug block contains
    nested blocks, and a bare regex would happily read the release block's
    contents for a debug query.
    """
    anchor = re.search(r"buildTypes\s*\{", text)
    if not anchor:
        return ""
    start = re.search(r"\b%s\s*\{" % re.escape(build_type), text[anchor.end():])
    if not start:
        return ""
    offset = anchor.end() + start.end()
    depth = 1
    index = offset
    while index < len(text) and depth > 0:
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
        index += 1
    block = text[offset:index]
    found = re.search(r"versionNameSuffix\s*=?\s*[\"']([^\"']*)[\"']", block)
    return found.group(1) if found else ""


def read_gradle_version(gradle_path, build_types=("debug", "release")):
    with open(gradle_path, "r") as handle:
        text = handle.read()
    code = VERSION_CODE.search(text)
    name = VERSION_NAME.search(text)
    min_sdk = MIN_SDK.search(text)
    if not code or not name:
        raise ValueError("could not read versionCode/versionName from %s" % gradle_path)
    suffixes = dict((build_type, _build_type_suffix(text, build_type))
                    for build_type in build_types)
    return GradleVersion(int(code.group(1)), name.group(1),
                         int(min_sdk.group(1)) if min_sdk else None, suffixes)


def sdk_dir(repo_root):
    """`sdk.dir` from local.properties, or $ANDROID_HOME / $ANDROID_SDK_ROOT."""
    path = os.path.join(repo_root, "local.properties")
    if os.path.exists(path):
        with open(path, "r") as handle:
            for line in handle:
                line = line.strip()
                if line.startswith("sdk.dir="):
                    return line.split("=", 1)[1].strip()
    return os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")


def find_aapt2(repo_root):
    """aapt2 on PATH, else the newest build-tools copy under the SDK."""
    from shutil import which
    found = which("aapt2")
    if found:
        return found
    root = sdk_dir(repo_root)
    if not root:
        return None
    build_tools = os.path.join(root, "build-tools")
    if not os.path.isdir(build_tools):
        return None
    for version in sorted(os.listdir(build_tools), reverse=True):
        candidate = os.path.join(build_tools, version, "aapt2")
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return None


class ApkInfo(object):
    def __init__(self, version_code=None, version_name=None, min_sdk=None,
                 package=None, source="aapt2", warning=None):
        self.version_code = version_code
        self.version_name = version_name
        self.min_sdk = min_sdk
        self.package = package
        self.source = source
        self.warning = warning


def _badging(aapt2, apk_path):
    result = subprocess.run([aapt2, "dump", "badging", apk_path],
                            capture_output=True, text=True)
    if result.returncode != 0:
        return None
    text = result.stdout
    package_line = re.search(r"^package: (.*)$", text, re.MULTILINE)
    info = ApkInfo(source="aapt2")
    if package_line:
        fields = dict(re.findall(r"(\w+)='([^']*)'", package_line.group(1)))
        info.package = fields.get("name")
        if fields.get("versionCode", "").isdigit():
            info.version_code = int(fields["versionCode"])
        info.version_name = fields.get("versionName")
    sdk_line = re.search(r"^sdkVersion:'(\d+)'", text, re.MULTILINE)
    if sdk_line:
        info.min_sdk = int(sdk_line.group(1))
    return info


def read_apk(apk_path, repo_root):
    """What the APK says about itself.

    Uses `aapt2 dump badging` when one can be found. Without it the binary
    AndroidManifest.xml cannot be decoded with the standard library, so the
    fallback only confirms the file IS an APK and returns a warning saying the
    versionCode could not be checked.
    """
    aapt2 = find_aapt2(repo_root)
    if aapt2:
        info = _badging(aapt2, apk_path)
        if info is not None:
            return info
    if not zipfile.is_zipfile(apk_path):
        raise ValueError("%s is not a zip/APK" % apk_path)
    with zipfile.ZipFile(apk_path) as archive:
        names = set(archive.namelist())
    if "AndroidManifest.xml" not in names:
        raise ValueError("%s has no AndroidManifest.xml -- not an APK" % apk_path)
    signed = any(n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC"))
                 for n in names)
    return ApkInfo(
        source="zipfile",
        warning="aapt2 was not found (not on PATH, and none under the SDK in "
                "local.properties): the APK's versionCode/versionName could NOT be verified "
                "against app/build.gradle. It is a valid APK%s."
                % (" with a v1 signature block" if signed else ""))
