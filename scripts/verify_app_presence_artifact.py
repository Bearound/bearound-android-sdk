#!/usr/bin/env python3
"""Verify the app presence declaration evidence inside a final Android artifact.

The host app applies scripts/app-presence-evidence.gradle, which generates the asset
bearound_app_presence_declarations.json from the merged manifest:

    {"schemaVersion": 1, "applicationId": "<id>", "packages": ["<sorted>", ...]}

This script opens the FINAL artifact (APK or AAB), reads its manifest and the asset, and
fails unless:
  - the asset exists and follows schema v1 (packages sorted, unique, valid names);
  - asset applicationId equals the manifest package (the applicationId);
  - the asset package set equals EXACTLY the <queries><package android:name/> set of the
    final manifest.

Manifest extraction:
  - APK: `aapt2 dump xmltree --file AndroidManifest.xml <apk>`. aapt2 comes from --aapt2,
    else $ANDROID_HOME / $ANDROID_SDK_ROOT build-tools (newest version first).
  - AAB: `bundletool dump manifest --bundle <aab>` (the base module manifest). bundletool
    comes from --bundletool, else the `bundletool` executable on PATH. When bundletool is
    not available the AAB check exits 2 (tool missing); verify the APK instead.

Exit codes: 0 verified, 1 evidence diverges or is missing, 2 usage or tool error.
Stdlib only (Python 3.8+).
"""

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ASSET_NAME = "bearound_app_presence_declarations.json"
SCHEMA_VERSION = 1
ANDROID_NS = "http://schemas.android.com/apk/res/android"
PACKAGE_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$")
_ELEMENT_RE = re.compile(r"^E: (?P<name>\S+)")
_ATTR_RE = re.compile(r'^A: (?P<key>[^=(]+?)(?:\(0x[0-9a-fA-F]+\))?=(?P<value>.*)$')
_RAW_RE = re.compile(r'\(Raw: "(?P<raw>.*)"\)\s*$')


class VerificationError(Exception):
    """The artifact is not conforming (exit 1)."""


class ToolError(Exception):
    """A required tool or input is unavailable (exit 2)."""


def _run(command):
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    except OSError as error:
        raise ToolError("could not run %s: %s" % (command[0], error))
    if result.returncode != 0:
        raise ToolError("command failed (%d): %s\n%s" % (result.returncode, " ".join(command), result.stderr.strip()))
    return result.stdout


def _attr_value(raw_value):
    match = _RAW_RE.search(raw_value)
    if match:
        return match.group("raw")
    value = raw_value.strip()
    if len(value) >= 2 and value[0] == '"':
        return value[1:value.index('"', 1)]
    return value


def parse_xmltree(dump):
    """Parse `aapt2 dump xmltree` text into (applicationId, sorted packages under queries).

    aapt2 indents children deeper than their parent but not by a fixed step, so the tree is
    rebuilt from relative indentation with a stack.
    """
    stack = []  # list of (indent, element dict)
    root = None
    for line in dump.splitlines():
        stripped = line.lstrip(" ")
        if not stripped:
            continue
        indent = len(line) - len(stripped)
        element = _ELEMENT_RE.match(stripped)
        attribute = _ATTR_RE.match(stripped)
        if element:
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = {"name": element.group("name"), "attrs": {}, "children": []}
            if stack:
                stack[-1][1]["children"].append(node)
            elif root is None:
                root = node
            stack.append((indent, node))
        elif attribute:
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1]["attrs"][attribute.group("key").strip()] = _attr_value(attribute.group("value"))
    if root is None or root["name"] != "manifest":
        raise ToolError("could not find <manifest> in the aapt2 dump")
    application_id = root["attrs"].get("package")
    if not application_id:
        raise ToolError("manifest has no package attribute")
    packages = []
    for queries in (c for c in root["children"] if c["name"] == "queries"):
        for child in queries["children"]:
            if child["name"] == "package":
                name = child["attrs"].get(ANDROID_NS + ":name")
                if name:
                    packages.append(name)
    return application_id, sorted(set(packages))


def parse_manifest_xml(text):
    """Parse a plain-text AndroidManifest.xml (bundletool output)."""
    try:
        root = ET.fromstring(text.strip())
    except ET.ParseError as error:
        raise ToolError("manifest XML is malformed: %s" % error)
    if root.tag != "manifest":
        raise ToolError("could not find <manifest> in the bundletool output")
    application_id = root.get("package")
    if not application_id:
        raise ToolError("manifest has no package attribute")
    packages = []
    for queries in root.findall("queries"):
        for package in queries.findall("package"):
            name = package.get("{%s}name" % ANDROID_NS)
            if name:
                packages.append(name)
    return application_id, sorted(set(packages))


def find_aapt2(explicit=None):
    if explicit:
        return explicit
    on_path = shutil.which("aapt2")
    if on_path:
        return on_path
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env)
        if not sdk:
            continue
        candidates = glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2"))

        def version_key(path):
            version = os.path.basename(os.path.dirname(path))
            return [int(p) if p.isdigit() else 0 for p in re.split(r"[.-]", version)]

        candidates.sort(key=version_key, reverse=True)
        if candidates:
            return candidates[0]
    raise ToolError("aapt2 not found: pass --aapt2 or set ANDROID_HOME")


def find_bundletool(explicit=None):
    if explicit:
        return [explicit] if not explicit.endswith(".jar") else ["java", "-jar", explicit]
    on_path = shutil.which("bundletool")
    if on_path:
        return [on_path]
    raise ToolError("bundletool not found: pass --bundletool, or verify the APK instead of the AAB")


def read_manifest(artifact, aapt2=None, bundletool=None, runner=_run):
    if artifact.endswith(".aab"):
        output = runner(find_bundletool(bundletool) + ["dump", "manifest", "--bundle", artifact])
        return parse_manifest_xml(output)
    output = runner([find_aapt2(aapt2), "dump", "xmltree", "--file", "AndroidManifest.xml", artifact])
    return parse_xmltree(output)


def read_asset(artifact):
    entry = ("base/assets/" if artifact.endswith(".aab") else "assets/") + ASSET_NAME
    try:
        with zipfile.ZipFile(artifact) as archive:
            try:
                data = archive.read(entry)
            except KeyError:
                raise VerificationError("declaration asset %s is missing from %s" % (entry, artifact))
    except zipfile.BadZipFile:
        raise ToolError("%s is not a zip archive" % artifact)
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise VerificationError("declaration asset is not valid JSON")


def check_asset(asset):
    if not isinstance(asset, dict):
        raise VerificationError("declaration asset must be a JSON object")
    version = asset.get("schemaVersion")
    if isinstance(version, bool) or version != SCHEMA_VERSION:
        raise VerificationError("declaration asset schemaVersion must be %d" % SCHEMA_VERSION)
    application_id = asset.get("applicationId")
    if not isinstance(application_id, str) or not application_id:
        raise VerificationError("declaration asset applicationId must be a non-empty string")
    packages = asset.get("packages")
    if not isinstance(packages, list) or not all(isinstance(p, str) for p in packages):
        raise VerificationError("declaration asset packages must be a list of strings")
    invalid = [p for p in packages if not PACKAGE_RE.match(p)]
    if invalid:
        raise VerificationError("declaration asset has invalid package names: %s" % invalid)
    if packages != sorted(set(packages)):
        raise VerificationError("declaration asset packages must be sorted and unique")
    return application_id, packages


def verify(artifact, aapt2=None, bundletool=None, runner=_run):
    """Return a report dict; raise VerificationError or ToolError when not conforming."""
    if not os.path.isfile(artifact):
        raise ToolError("artifact not found: %s" % artifact)
    manifest_id, manifest_packages = read_manifest(artifact, aapt2=aapt2, bundletool=bundletool, runner=runner)
    asset_id, asset_packages = check_asset(read_asset(artifact))
    if asset_id != manifest_id:
        raise VerificationError("applicationId diverges: asset %r, manifest %r" % (asset_id, manifest_id))
    if set(asset_packages) != set(manifest_packages):
        only_asset = sorted(set(asset_packages) - set(manifest_packages))
        only_manifest = sorted(set(manifest_packages) - set(asset_packages))
        raise VerificationError(
            "package set diverges: only in asset %s, only in manifest <queries> %s" % (only_asset, only_manifest)
        )
    return {"artifact": artifact, "applicationId": manifest_id, "packages": manifest_packages, "verified": True}


def resolve_artifacts(path):
    if os.path.isdir(path):
        found = sorted(glob.glob(os.path.join(path, "*.apk")) + glob.glob(os.path.join(path, "*.aab")))
        if not found:
            raise ToolError("no .apk or .aab found in %s" % path)
        return found
    return [path]


def main(argv=None):
    parser = argparse.ArgumentParser(description="Verify app presence declaration evidence in an APK or AAB.")
    parser.add_argument("artifact", help="APK, AAB, or a directory containing them")
    parser.add_argument("--aapt2", help="path to aapt2 (APK)")
    parser.add_argument("--bundletool", help="path to bundletool executable or jar (AAB)")
    parser.add_argument("--report", help="write a JSON report to this path")
    args = parser.parse_args(argv)

    reports = []
    try:
        for artifact in resolve_artifacts(args.artifact):
            reports.append(verify(artifact, aapt2=args.aapt2, bundletool=args.bundletool))
    except VerificationError as error:
        print("app presence evidence: FAILED: %s" % error, file=sys.stderr)
        return 1
    except ToolError as error:
        print("app presence evidence: ERROR: %s" % error, file=sys.stderr)
        return 2
    if args.report:
        with open(args.report, "w") as handle:
            json.dump(reports, handle, indent=2, sort_keys=True)
    for report in reports:
        print("app presence evidence: OK %s (%s, %d packages)" % (report["artifact"], report["applicationId"], len(report["packages"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
