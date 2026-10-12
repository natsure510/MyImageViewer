"""CI-only release helpers. Never generate keys, move tags, or publish a release."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def command(args, env=None):
    # Signing tools may include aliases/keystore details in errors. Never echo output on failure.
    result = subprocess.run(args, env=env, capture_output=True, text=True, check=False)
    require(result.returncode == 0, f"{Path(args[0]).name} failed; check configuration and inputs.")
    return result.stdout.strip()


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def version(tag, build_file=Path('app/build.gradle.kts')):
    require(re.fullmatch(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', tag),
            'Tag must be a stable version such as v1.0.2.')
    text = build_file.read_text()
    names = re.findall(r'^\s*versionName\s*=\s*"([^"\n]+)"\s*(?://[^\n]*)?$', text, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*(?://[^\n]*)?$', text, re.M)
    require(len(names) == 1 and len(codes) == 1, 'Use one literal versionName and versionCode in app/build.gradle.kts.')
    require(tag == 'v' + names[0], 'Tag does not match versionName. Do not move an existing tag.')
    require(0 < int(codes[0]) <= 2100000000, 'versionCode must be a positive Android version code.')
    return names[0], int(codes[0])


def apk_tool(name):
    return str(Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0' / name)


def verify_apk_metadata(apk, name, code):
    for field, expected in [('application-id', 'com.example.myimageviewer'),
                            ('version-name', name), ('version-code', str(code)), ('debuggable', 'false')]:
        require(command(['apkanalyzer', 'manifest', field, str(apk)]) == expected,
                f'APK manifest {field} does not match release requirements.')


def prepare(tag, source, directory):
    name, code = version(tag)
    require(command(['git', 'rev-parse', 'HEAD']) == source, 'Checkout is not the event commit.')
    require(not command(['git', 'status', '--porcelain', '--untracked-files=no']), 'Tracked source files changed during build.')
    original = Path('app/build/outputs/apk/release/app-release-unsigned.apk')
    require(original.is_file(), 'Unsigned release APK is missing; review the release build/signing configuration.')
    verify_apk_metadata(original, name, code)
    directory.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(original, directory / 'unsigned.apk')
    metadata = dict(tag=tag, source=source, version_name=name, version_code=code, unsigned_sha256=sha256(original))
    (directory / 'build.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f"apk_sha256={metadata['unsigned_sha256']}\n")


def sign(tag, source, directory, expected_hash):
    name, code = version(tag)
    metadata = json.loads((directory / 'build.json').read_text())
    require(metadata == dict(tag=tag, source=source, version_name=name, version_code=code,
                             unsigned_sha256=expected_hash), 'Build metadata does not match the build job.')
    unsigned = directory / 'unsigned.apk'
    require(sha256(unsigned) == expected_hash, 'Unsigned APK digest mismatch.')
    verify_apk_metadata(unsigned, name, code)
    names = ['ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD']
    missing = [key for key in names if not os.environ.get(key)]
    require(not missing, 'Missing Actions Secrets: ' + ', '.join(missing))
    signed = directory / f'MyImageViewer-{tag}.apk'
    require(not signed.exists(), 'Signed output already exists; refusing to replace it.')
    key_root = Path(os.environ['RUNNER_TEMP']) / 'android-signing'
    key_root.mkdir(mode=0o700, parents=True, exist_ok=True)
    # This directory is also removed by an always() workflow step if the process is interrupted.
    with tempfile.TemporaryDirectory(dir=key_root) as temporary:
        key = Path(temporary) / 'release.keystore'
        try:
            raw = base64.b64decode(''.join(os.environ['ANDROID_KEYSTORE_BASE64'].split()), validate=True)
        except (ValueError, UnicodeError):
            raise ReleaseError('ANDROID_KEYSTORE_BASE64 is not valid Base64.') from None
        require(raw, 'Decoded keystore is empty.')
        with key.open('xb') as output:
            key.chmod(0o600)
            output.write(raw)
        del raw
        # Passwords are passed through environment variables, not command-line arguments.
        signing_env = {k: v for k, v in os.environ.items() if k != 'ANDROID_KEYSTORE_BASE64'}
        command([apk_tool('zipalign'), '-c', '-P', '16', '4', str(unsigned)])
        command([apk_tool('apksigner'), 'sign', '--ks', str(key),
                 '--ks-key-alias', os.environ['ANDROID_KEY_ALIAS'],
                 '--ks-pass', 'env:ANDROID_KEYSTORE_PASSWORD', '--key-pass', 'env:ANDROID_KEY_PASSWORD',
                 '--out', str(signed), str(unsigned)], env=signing_env)
        command([apk_tool('apksigner'), 'verify', str(signed)], env=signing_env)
        command([apk_tool('zipalign'), '-c', '-P', '16', '4', str(signed)])
    verify_apk_metadata(signed, name, code)
    metadata['signed_sha256'] = sha256(signed)
    (directory / 'signed.json').write_text(json.dumps(metadata, indent=2) + '\n')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ReleaseError('Unexpected API redirect; refusing to forward credentials.')


class GitHub:
    def __init__(self):
        self.repository = os.environ['GITHUB_REPOSITORY']
        require(re.fullmatch(r'[\w.-]+/[\w.-]+', self.repository), 'Invalid GitHub repository.')
        self.token = os.environ['GH_TOKEN']
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, *, payload=None, binary=None):
        host = 'uploads.github.com' if binary is not None else 'api.github.com'
        headers = {'Authorization': f'Bearer {self.token}', 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'MyImageViewer-release'}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode()
            headers['Content-Type'] = 'application/json'
        if binary is not None:
            data = binary
            headers['Content-Type'] = 'application/vnd.android.package-archive'
        request = urllib.request.Request(f'https://{host}/repos/{self.repository}/{path}', data=data, headers=headers)
        try:
            with self.opener.open(request, timeout=120) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise ReleaseError(f'GitHub API failed (HTTP {error.code}); no existing release was overwritten.') from None
        except urllib.error.URLError:
            raise ReleaseError('GitHub API connection failed.') from None

    def ensure_new(self, tag):
        # List authenticated releases so drafts are included; never mistake an API error for absence.
        for page in range(1, 1001):
            releases = self.request(f'releases?per_page=100&page={page}')
            require(isinstance(releases, list), 'Unexpected releases API response.')
            require(not any(item['tag_name'] == tag for item in releases),
                    'A Draft or published Release already exists for this tag. Refusing to overwrite it.')
            if len(releases) < 100:
                return
        raise ReleaseError('Could not complete existing release inspection.')

    def verify_tag(self, tag, source):
        obj = self.request('git/ref/tags/' + urllib.parse.quote(tag, safe=''))['object']
        for _ in range(8):
            if obj['type'] == 'commit':
                require(obj['sha'] == source, 'Remote tag no longer matches the tested source commit.')
                return
            require(obj['type'] == 'tag', 'Tag does not point to a commit.')
            obj = self.request('git/tags/' + obj['sha'])['object']
        raise ReleaseError('Excessively nested annotated tag.')

    def publish_draft(self, tag, source, directory):
        name, code = version(tag)
        metadata = json.loads((directory / 'signed.json').read_text())
        apk = directory / f'MyImageViewer-{tag}.apk'
        require(metadata['tag'] == tag and metadata['source'] == source and
                metadata['version_name'] == name and metadata['version_code'] == code and
                metadata['signed_sha256'] == sha256(apk), 'Signed APK metadata/digest mismatch.')
        self.verify_tag(tag, source)
        self.ensure_new(tag)
        digest = metadata['signed_sha256']
        body = (f'大学の時間割など、選択した画像をすぐに表示する Android 画像ビューアです。\n\n'
                f'画像の選択・内部保存・再起動時の復元・長押しでの変更に対応しています。\n\n'
                f'- Version: {name} (versionCode {code})\n- Source commit: `{source}`\n'
                f'- APK SHA-256: `{digest}`\n\n'
                'JVM テストと Android Lint を通過したソースから生成し、既存のリリース鍵で署名しています。'
                '実機確認と変更内容の追記後、人間が手動で公開してください。')
        release = self.request('releases', payload={'tag_name': tag, 'target_commitish': source,
                               'name': f'MyImageViewer {tag}', 'body': body, 'draft': True, 'prerelease': False})
        require(release['draft'], 'GitHub did not create a Draft Release.')
        # If upload fails, leave the draft intact for inspection; never delete/replace/re-publish it.
        asset = self.request(f"releases/{release['id']}/assets?name=" + urllib.parse.quote(apk.name), binary=apk.read_bytes())
        require(asset['state'] == 'uploaded' and asset['size'] == apk.stat().st_size, 'APK upload is incomplete.')
        if asset.get('digest'):
            require(asset['digest'] == 'sha256:' + digest, 'Uploaded APK digest mismatch.')
        print('Draft Release: ' + release['html_url'])
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
                output.write(f"[MyImageViewer {tag} (Draft)]({release['html_url']})\n\nCommit: `{source}`\n\nAPK SHA-256: `{digest}`\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['validate-version', 'ensure-new', 'prepare', 'sign', 'draft'])
    args = parser.parse_args()
    tag = os.environ['VERSION_TAG']
    version(tag)
    if args.operation == 'validate-version':
        print('Tag and app version match.')
    elif args.operation == 'ensure-new':
        GitHub().ensure_new(tag)
    else:
        source = os.environ['GITHUB_SHA']
        require(re.fullmatch(r'[0-9a-f]{40}', source), 'Invalid source commit.')
        directory = Path(os.environ['RELEASE_DIR'])
        if args.operation == 'prepare':
            prepare(tag, source, directory)
        elif args.operation == 'sign':
            sign(tag, source, directory, os.environ['EXPECTED_APK_SHA256'])
        else:
            GitHub().publish_draft(tag, source, directory)


if __name__ == '__main__':
    try:
        main()
    except (ReleaseError, OSError, ValueError, KeyError) as error:
        # Never print arbitrary tool/API exceptions which may contain secret material.
        print(str(error) if isinstance(error, ReleaseError) else 'Release input/configuration error.', file=sys.stderr)
        sys.exit(1)
