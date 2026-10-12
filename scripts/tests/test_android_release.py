import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error

SPEC = importlib.util.spec_from_file_location('release', Path(__file__).parents[1] / 'android_release.py')
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

TAG = 'v1.0.2'
SOURCE = 'a' * 40


class VersionTests(unittest.TestCase):
    def test_only_matching_stable_tag_and_positive_code_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            build = Path(directory) / 'build.gradle.kts'
            build.write_text('versionName = "1.0.2"\nversionCode = 3\n')
            self.assertEqual(release.version(TAG, build), ('1.0.2', 3))
            for tag in ['v1.0.1', '1.0.2', 'v1.0.2-beta', 'v01.0.2', 'v1.0.2;echo secret']:
                with self.subTest(tag=tag), self.assertRaises(release.ReleaseError):
                    release.version(tag, build)
            build.write_text('versionName = "1.0.2"\nversionCode = 0\n')
            with self.assertRaises(release.ReleaseError):
                release.version(TAG, build)

    def test_computed_or_duplicate_version_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            build = Path(directory) / 'build.gradle.kts'
            for contents in ['versionName = providers.gradleProperty("version")\nversionCode = 3',
                             'versionName = "1.0.2"\nversionName = "1.0.2"\nversionCode = 3']:
                build.write_text(contents)
                with self.assertRaises(release.ReleaseError):
                    release.version(TAG, build)

    def test_tool_error_does_not_echo_sensitive_stdout_or_stderr(self):
        result = subprocess.CompletedProcess([], 1, stdout='private-output', stderr='private-password')
        with patch.object(release.subprocess, 'run', return_value=result):
            with self.assertRaises(release.ReleaseError) as caught:
                release.command(['apksigner', 'sign'])
        self.assertNotIn('private', str(caught.exception))


class SigningTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / 'artifact'
        self.directory.mkdir()
        (self.directory / 'unsigned.apk').write_bytes(b'unsigned-fixture')
        self.digest = release.sha256(self.directory / 'unsigned.apk')
        self.metadata = dict(tag=TAG, source=SOURCE, version_name='1.0.2', version_code=3,
                             unsigned_sha256=self.digest)
        (self.directory / 'build.json').write_text(json.dumps(self.metadata))
        self.env = patch.dict(os.environ, {'RUNNER_TEMP': str(self.root), 'ANDROID_HOME': '/sdk'}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        for name, result in [('version', ('1.0.2', 3)), ('verify_apk_metadata', None)]:
            mock = patch.object(release, name, return_value=result)
            mock.start()
            self.addCleanup(mock.stop)

    def secrets(self):
        # Inert fixture bytes only: no cryptographic key is generated or used by these tests.
        return patch.dict(os.environ, {
            'ANDROID_KEYSTORE_BASE64': base64.b64encode(b'not-a-keystore-fixture').decode(),
            'ANDROID_KEYSTORE_PASSWORD': 'test-store-password',
            'ANDROID_KEY_ALIAS': 'test-alias', 'ANDROID_KEY_PASSWORD': 'test-key-password',
        })

    def test_missing_secrets_prevents_signing(self):
        with patch.object(release, 'command') as command, self.assertRaises(release.ReleaseError) as caught:
            release.sign(TAG, SOURCE, self.directory, self.digest)
        command.assert_not_called()
        self.assertIn('Missing Actions Secrets', str(caught.exception))

    def test_modified_download_prevents_signing(self):
        (self.directory / 'unsigned.apk').write_bytes(b'changed')
        with patch.object(release, 'command') as command, self.assertRaises(release.ReleaseError):
            release.sign(TAG, SOURCE, self.directory, self.digest)
        command.assert_not_called()

    def test_other_commit_metadata_prevents_signing(self):
        self.metadata['source'] = 'b' * 40
        (self.directory / 'build.json').write_text(json.dumps(self.metadata))
        with self.assertRaises(release.ReleaseError):
            release.sign(TAG, SOURCE, self.directory, self.digest)

    def test_invalid_base64_cleans_temporary_directory(self):
        with self.secrets(), patch.dict(os.environ, {'ANDROID_KEYSTORE_BASE64': '%%%'}):
            with self.assertRaises(release.ReleaseError):
                release.sign(TAG, SOURCE, self.directory, self.digest)
        self.assertEqual(list((self.root / 'android-signing').iterdir()), [])

    def test_sign_failure_cleans_key_and_does_not_create_verification_record(self):
        def fail_sign(args, env=None):
            if 'sign' in args:
                key = Path(args[args.index('--ks') + 1])
                self.assertTrue(key.is_file())
                self.assertEqual(key.stat().st_mode & 0o777, 0o600)
                self.assertNotIn('test-store-password', args)
                self.assertNotIn('test-key-password', args)
                self.assertNotIn('ANDROID_KEYSTORE_BASE64', env)
                raise release.ReleaseError('Signing failed')
            return ''
        with self.secrets(), patch.object(release, 'command', side_effect=fail_sign):
            with self.assertRaises(release.ReleaseError):
                release.sign(TAG, SOURCE, self.directory, self.digest)
        self.assertEqual(list((self.root / 'android-signing').iterdir()), [])
        self.assertFalse((self.directory / 'signed.json').exists())

    def test_verification_failure_never_creates_publish_input(self):
        def fail_verify(args, env=None):
            if 'sign' in args:
                Path(args[args.index('--out') + 1]).write_bytes(b'signed-fixture')
            if 'verify' in args:
                raise release.ReleaseError('Invalid signature')
            return ''
        with self.secrets(), patch.object(release, 'command', side_effect=fail_verify):
            with self.assertRaises(release.ReleaseError):
                release.sign(TAG, SOURCE, self.directory, self.digest)
        self.assertFalse((self.directory / 'signed.json').exists())
        self.assertEqual(list((self.root / 'android-signing').iterdir()), [])

    def test_success_records_verified_digest_and_removes_key(self):
        def fake_tool(args, env=None):
            if 'sign' in args:
                Path(args[args.index('--out') + 1]).write_bytes(b'signed-fixture')
            return ''
        with self.secrets(), patch.object(release, 'command', side_effect=fake_tool) as command:
            release.sign(TAG, SOURCE, self.directory, self.digest)
        self.assertTrue(any('verify' in call.args[0] for call in command.call_args_list))
        data = json.loads((self.directory / 'signed.json').read_text())
        self.assertEqual(data['signed_sha256'], release.sha256(self.directory / f'MyImageViewer-{TAG}.apk'))
        self.assertEqual(list((self.root / 'android-signing').iterdir()), [])


class GitHubTests(unittest.TestCase):
    def setUp(self):
        env = patch.dict(os.environ, {'GITHUB_REPOSITORY': 'owner/repo', 'GH_TOKEN': 'fake-token'}, clear=True)
        env.start()
        self.addCleanup(env.stop)
        self.github = release.GitHub()

    def test_api_errors_including_404_never_mean_release_is_absent(self):
        for code in [401, 403, 404, 429, 500]:
            error = urllib.error.HTTPError('https://api.github.com/example', code, 'error', {}, None)
            with self.subTest(code=code), patch.object(self.github.opener, 'open', side_effect=error):
                with self.assertRaises(release.ReleaseError):
                    self.github.ensure_new(TAG)

    def test_existing_draft_or_public_release_cannot_be_replaced(self):
        for draft in [True, False]:
            with patch.object(self.github, 'request', return_value=[{'id': 1, 'draft': draft, 'tag_name': TAG}]) as request:
                with self.assertRaises(release.ReleaseError):
                    self.github.ensure_new(TAG)
                self.assertEqual(request.call_count, 1)
                self.assertNotIn('payload', request.call_args.kwargs)

    def test_existing_draft_on_second_page_is_rejected(self):
        first = [{'tag_name': f'v0.0.{index}', 'draft': False} for index in range(100)]
        with patch.object(self.github, 'request', side_effect=[first, [{'tag_name': TAG, 'draft': True}]]) as request:
            with self.assertRaises(release.ReleaseError):
                self.github.ensure_new(TAG)
            self.assertEqual(request.call_count, 2)

    def test_annotated_and_lightweight_tags_resolve_to_expected_commit(self):
        for responses in [
            [{'object': {'type': 'commit', 'sha': SOURCE}}],
            [{'object': {'type': 'tag', 'sha': 'c' * 40}}, {'object': {'type': 'commit', 'sha': SOURCE}}],
        ]:
            with patch.object(self.github, 'request', side_effect=responses):
                self.github.verify_tag(TAG, SOURCE)

    def test_moved_tag_is_rejected(self):
        with patch.object(self.github, 'request', return_value={'object': {'type': 'commit', 'sha': 'b' * 40}}):
            with self.assertRaises(release.ReleaseError):
                self.github.verify_tag(TAG, SOURCE)

    def publish_fixture(self, directory):
        apk = directory / f'MyImageViewer-{TAG}.apk'
        apk.write_bytes(b'signed-fixture')
        metadata = dict(tag=TAG, source=SOURCE, version_name='1.0.2', version_code=3,
                        signed_sha256=release.sha256(apk))
        (directory / 'signed.json').write_text(json.dumps(metadata))
        return apk

    def test_create_is_draft_only_and_upload_happens_once(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(release, 'version', return_value=('1.0.2', 3)):
            directory = Path(directory)
            apk = self.publish_fixture(directory)
            responses = [{'object': {'type': 'commit', 'sha': SOURCE}}, [],
                         {'id': 42, 'draft': True, 'html_url': 'https://github.com/owner/repo/releases/tag/test'},
                         {'state': 'uploaded', 'size': apk.stat().st_size, 'digest': 'sha256:' + release.sha256(apk)}]
            with patch.object(self.github, 'request', side_effect=responses) as request, contextlib.redirect_stdout(io.StringIO()):
                self.github.publish_draft(TAG, SOURCE, directory)
            creates = [call for call in request.call_args_list if 'payload' in call.kwargs]
            self.assertEqual(len(creates), 1)
            self.assertTrue(creates[0].kwargs['payload']['draft'])
            self.assertEqual(creates[0].kwargs['payload']['target_commitish'], SOURCE)
            self.assertEqual(len([c for c in request.call_args_list if 'binary' in c.kwargs]), 1)

    def test_failed_upload_leaves_draft_and_never_deletes_or_retries(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(release, 'version', return_value=('1.0.2', 3)):
            directory = Path(directory)
            self.publish_fixture(directory)
            responses = [{'object': {'type': 'commit', 'sha': SOURCE}}, [],
                         {'id': 42, 'draft': True}, release.ReleaseError('Upload failed')]
            with patch.object(self.github, 'request', side_effect=responses) as request:
                with self.assertRaises(release.ReleaseError):
                    self.github.publish_draft(TAG, SOURCE, directory)
            self.assertEqual(request.call_count, 4)

    def test_changed_signed_file_is_rejected_before_network(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(release, 'version', return_value=('1.0.2', 3)):
            directory = Path(directory)
            apk = self.publish_fixture(directory)
            apk.write_bytes(b'tampered')
            with patch.object(self.github, 'request') as request, self.assertRaises(release.ReleaseError):
                self.github.publish_draft(TAG, SOURCE, directory)
            request.assert_not_called()

    def test_redirect_does_not_forward_token(self):
        with self.assertRaises(release.ReleaseError):
            release.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.com')


if __name__ == '__main__':
    unittest.main()
