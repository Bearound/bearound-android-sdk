"""Tests for verify_app_presence_artifact.py (stdlib unittest only).

Run: python3 -m unittest discover -s scripts -p test_app_presence_evidence.py
"""

import glob
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_app_presence_artifact as verifier  # noqa: E402

APP_ID = "io.bearound.host"
FIXTURE = "io.bearound.fixture.presence"
OTHER = "com.example.other"


def xmltree_dump(application_id, packages):
    """A dump shaped like real `aapt2 dump xmltree` output (irregular indentation)."""
    lines = [
        "N: android=http://schemas.android.com/apk/res/android (line=1)",
        "  E: manifest (line=1)",
        "    A: http://schemas.android.com/apk/res/android:compileSdkVersion(0x01010572)=36",
        '    A: package="%s" (Raw: "%s")' % (application_id, application_id),
        "      E: queries (line=2)",
    ]
    for index, name in enumerate(packages):
        lines += [
            "          E: package (line=%d)" % (3 + index),
            '            A: http://schemas.android.com/apk/res/android:name(0x01010003)="%s" (Raw: "%s")' % (name, name),
        ]
    lines += [
        "          E: intent (line=9)",
        "              E: action (line=9)",
        '                A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.VIEW" (Raw: "android.intent.action.VIEW")',
        "      E: application (line=10)",
        "          E: activity (line=11)",
        '            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.not.a.query" (Raw: "com.not.a.query")',
    ]
    return "\n".join(lines) + "\n"


def bundletool_manifest(application_id, packages):
    entries = "".join('<package android:name="%s"/>' % name for name in packages)
    return (
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s">\n'
        "  <queries>%s<intent><action android:name=\"android.intent.action.VIEW\"/></intent></queries>\n"
        '  <application><activity android:name="com.not.a.query"/></application>\n'
        "</manifest>\n" % (application_id, entries)
    )


def asset(application_id=APP_ID, packages=(FIXTURE, OTHER)):
    return json.dumps({"schemaVersion": 1, "applicationId": application_id, "packages": sorted(packages)})


class EvidenceTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="app-presence-evidence-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def make_archive(self, name, asset_content, asset_entry="assets/" + verifier.ASSET_NAME):
        path = os.path.join(self.tmp, name)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"binary-manifest-placeholder")
            if asset_content is not None:
                archive.writestr(asset_entry, asset_content)
        return path

    def fake_tool(self, name, output):
        """An executable that prints `output`, standing in for aapt2/bundletool."""
        output_file = os.path.join(self.tmp, name + ".out")
        with open(output_file, "w") as handle:
            handle.write(output)
        script = os.path.join(self.tmp, name)
        with open(script, "w") as handle:
            handle.write("#!%s\nimport sys\nsys.stdout.write(open(%r).read())\n" % (sys.executable, output_file))
        os.chmod(script, os.stat(script).st_mode | stat.S_IEXEC)
        return script


class ApkVerificationTest(EvidenceTestCase):
    def verify_apk(self, manifest_packages, asset_content, manifest_id=APP_ID):
        apk = self.make_archive("app.apk", asset_content)
        runner = lambda command: xmltree_dump(manifest_id, manifest_packages)  # noqa: E731
        return verifier.verify(apk, aapt2="aapt2", runner=runner)

    def test_matching_evidence_passes(self):
        report = self.verify_apk([FIXTURE, OTHER], asset())
        self.assertTrue(report["verified"])
        self.assertEqual(APP_ID, report["applicationId"])
        self.assertEqual(sorted([FIXTURE, OTHER]), report["packages"])

    def test_activity_names_and_intents_are_not_counted_as_queries(self):
        application_id, packages = verifier.parse_xmltree(xmltree_dump(APP_ID, [FIXTURE]))
        self.assertEqual(APP_ID, application_id)
        self.assertEqual([FIXTURE], packages)

    def test_removed_query_fails(self):
        with self.assertRaises(verifier.VerificationError) as context:
            self.verify_apk([FIXTURE], asset())
        self.assertIn(OTHER, str(context.exception))

    def test_divergent_asset_fails(self):
        with self.assertRaises(verifier.VerificationError):
            self.verify_apk([FIXTURE, OTHER], asset(packages=(FIXTURE, "com.example.extra", OTHER)))
        with self.assertRaises(verifier.VerificationError):
            self.verify_apk([FIXTURE, OTHER], json.dumps({"schemaVersion": 1, "applicationId": APP_ID, "packages": [OTHER, FIXTURE, FIXTURE]}))
        with self.assertRaises(verifier.VerificationError):
            self.verify_apk([FIXTURE, OTHER], asset().replace('"schemaVersion": 1', '"schemaVersion": 2'))
        with self.assertRaises(verifier.VerificationError):
            self.verify_apk([FIXTURE, OTHER], "{not json")

    def test_missing_asset_fails(self):
        with self.assertRaises(verifier.VerificationError) as context:
            self.verify_apk([FIXTURE, OTHER], None)
        self.assertIn("missing", str(context.exception))

    def test_wrong_application_id_fails(self):
        with self.assertRaises(verifier.VerificationError) as context:
            self.verify_apk([FIXTURE, OTHER], asset(application_id="io.bearound.other"))
        self.assertIn("applicationId", str(context.exception))

    def test_cli_exit_codes_with_injected_aapt2(self):
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify_app_presence_artifact.py")
        aapt2 = self.fake_tool("aapt2", xmltree_dump(APP_ID, [FIXTURE, OTHER]))
        good = self.make_archive("good.apk", asset())
        bad = self.make_archive("bad.apk", asset(packages=(FIXTURE,)))
        report = os.path.join(self.tmp, "report.json")

        ok = subprocess.run([sys.executable, script, good, "--aapt2", aapt2, "--report", report],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(0, ok.returncode, ok.stderr)
        with open(report) as handle:
            self.assertTrue(json.load(handle)[0]["verified"])

        failed = subprocess.run([sys.executable, script, bad, "--aapt2", aapt2],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(1, failed.returncode)

        missing_tool = subprocess.run([sys.executable, script, good, "--aapt2", os.path.join(self.tmp, "nope")],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(2, missing_tool.returncode)


class AabVerificationTest(EvidenceTestCase):
    def verify_aab(self, manifest_packages, asset_content, entry="base/assets/" + verifier.ASSET_NAME):
        aab = self.make_archive("app.aab", asset_content, asset_entry=entry)
        runner = lambda command: bundletool_manifest(APP_ID, manifest_packages)  # noqa: E731
        return verifier.verify(aab, bundletool="bundletool", runner=runner)

    def test_matching_bundle_passes(self):
        self.assertTrue(self.verify_aab([OTHER, FIXTURE], asset())["verified"])

    def test_removed_query_in_bundle_fails(self):
        with self.assertRaises(verifier.VerificationError):
            self.verify_aab([FIXTURE], asset())

    def test_asset_outside_base_module_counts_as_missing(self):
        with self.assertRaises(verifier.VerificationError):
            self.verify_aab([FIXTURE, OTHER], asset(), entry="assets/" + verifier.ASSET_NAME)


def _real_aapt2_and_platform():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Library/Android/sdk")
    tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
    platforms = sorted(glob.glob(os.path.join(sdk, "platforms", "android-*", "android.jar")))
    if not tools or not platforms:
        return None, None
    return tools[-1], platforms[-1]


class RealAapt2Test(EvidenceTestCase):
    """Builds a real APK with aapt2 when the Android SDK is installed; skipped otherwise."""

    def build_apk(self, aapt2, android_jar, packages, asset_content):
        manifest = os.path.join(self.tmp, "AndroidManifest.xml")
        queries = "".join('<package android:name="%s"/>' % name for name in packages)
        with open(manifest, "w") as handle:
            handle.write(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s">'
                "<queries>%s</queries><application/></manifest>" % (APP_ID, queries)
            )
        apk = os.path.join(self.tmp, "real-%d.apk" % len(packages))
        subprocess.run([aapt2, "link", "-o", apk, "-I", android_jar, "--manifest", manifest], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        with zipfile.ZipFile(apk, "a") as archive:
            archive.writestr("assets/" + verifier.ASSET_NAME, asset_content)
        return apk

    def test_real_aapt2_matches_and_detects_removed_query(self):
        aapt2, android_jar = _real_aapt2_and_platform()
        if aapt2 is None:
            self.skipTest("Android SDK build-tools/platform not installed")
        good = self.build_apk(aapt2, android_jar, [FIXTURE, OTHER], asset())
        self.assertTrue(verifier.verify(good, aapt2=aapt2)["verified"])

        removed = self.build_apk(aapt2, android_jar, [FIXTURE], asset())
        with self.assertRaises(verifier.VerificationError):
            verifier.verify(removed, aapt2=aapt2)


if __name__ == "__main__":
    unittest.main()
