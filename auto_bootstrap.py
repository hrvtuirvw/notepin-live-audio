"""Obtain owner bootstrap using a human-authenticated official Web session.

No password collection, account binding, recording controls or credential logging.
Private API compatibility is limited to the locally evidenced APNE1 flow.
"""
import argparse
import asyncio
import base64
import json
import os
import re
import shutil
import stat
import tempfile
import time
from pathlib import Path
from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey
from cryptography.hazmat.primitives.serialization import load_pem_private_key

WEB_ORIGIN = 'https://web.plaud.ai'
API_ORIGIN = 'https://api-apne1.plaud.ai'
# App-wide response encoding constant observed in the official client; NOT a
# user secret or an authorization credential. Cookies still authorize requests.
RESPONSE_ENCODING_KEY = b'8rRmMCskL3NaUiGr-UsmEEeI8NKH-ZfeGbpRGqSLpuI='

class BootstrapError(Exception):
    pass


def decode_response(text):
    if len(text) > 2_000_000:
        raise BootstrapError('API response exceeds limit')
    try:
        if not text.lstrip().startswith('{'):
            # Fernet checks version, HMAC and padding before JSON decoding.
            text = Fernet(RESPONSE_ENCODING_KEY).decrypt(text.strip().encode()).decode()
        obj = json.loads(text)
    except (ValueError, UnicodeError, InvalidToken):
        raise BootstrapError('API response integrity or format check failed') from None
    if not isinstance(obj, dict) or obj.get('status') != 0:
        raise BootstrapError('Official API did not authorize this operation')
    return obj


def rows(obj, keys):
    data = obj.get('data')
    if isinstance(data, dict):
        for key in keys:
            if isinstance(data.get(key), list):
                data = data[key]
                break
    if not isinstance(data, list):
        raise BootstrapError('Unsupported list schema')
    return [x for x in data if isinstance(x, dict)]


def device_serials(obj):
    return {x['sn'] for x in rows(obj, ('devices', 'device_list'))
            if isinstance(x.get('sn'), str)}


def owner_token(devices, workspaces, serial):
    matches = [x for x in rows(devices, ('devices', 'device_list')) if x.get('sn') == serial]
    if len(matches) != 1:
        raise BootstrapError('Target is absent or ambiguous in authenticated device list')
    device = matches[0]
    owner = device.get('owner_member_id')
    # Official app token getter uses WorkspaceContextManager member ID. Restrict
    # to an own personal workspace, never borrow another/shared device owner ID.
    eligible = [x for x in rows(workspaces, ('workspaces', 'workspace_list'))
                if x.get('workspace_id') == device.get('workspace_id')
                and x.get('member_id') == owner and x.get('workspace_type') in (0, '0')]
    if not device.get('workspace_id') or len(eligible) != 1:
        raise BootstrapError('Own personal workspace/member ownership could not be proven')
    owner_handshake(owner)
    return owner


def owner_handshake(token):
    # Exact header and 32-byte ASCII token matched the observed owner packet.
    # Reject rather than truncate/pad unknown token types or legacy protocols.
    if not isinstance(token, str) or not re.fullmatch(r'[0-9a-fA-F]{32}', token):
        raise BootstrapError('Unsupported bind-token format; no guessed handshake')
    return b'\x01\x01\x00\x02\x00\x00' + token.encode('ascii')


def build_material(address, serial, key_response, sign_response, token_response):
    if not re.fullmatch(r'(?:[0-9A-F]{2}:){5}[0-9A-F]{2}', address) or not serial:
        raise BootstrapError('Exact target MAC and serial required')
    if any(x.get('status') != 0 for x in (key_response, sign_response, token_response)):
        raise BootstrapError('Credential responses must all be successful')
    try:
        pem = base64.b64decode(key_response['data']['private_key_base64'], validate=True)
        key = load_pem_private_key(pem, None)
        if not isinstance(key, RSAPrivateKey) or key.key_size != 2048:
            raise BootstrapError('Unsupported account RSA key')
        signature = sign_response['data']['signature'][serial]
        if len(base64.b64decode(signature, validate=True)) != 256:
            raise BootstrapError('Invalid target signature')
        token = token_response['data']['token']
        handshake = owner_handshake(token)
        payload = dict(address=address, serial=serial, signature=signature,
                       privateKeyPem=pem.decode('ascii'),
                       ownerHandshake=base64.b64encode(handshake).decode(),
                       streamEnabled=False, streamVad=False)
    except (KeyError, TypeError, ValueError, UnicodeError):
        raise BootstrapError('Unsupported credential schema or invalid credential material') from None
    # Keep only the selected device, not all device signatures/account metadata.
    auth = {'sign': {'status': 0, 'data': {'signature': {serial: signature}}}}
    return {
        'private-research-auth.json': (json.dumps(auth) + '\n').encode(),
        'private-account-key.pem': pem,
        'private-owner-handshake.bin': handshake,
        'private-android-bootstrap.json': (json.dumps(payload) + '\n').encode(),
    }


def save_material(directory, material):
    directory = Path(directory).absolute()
    if directory.exists() or directory.is_symlink():
        raise BootstrapError('Output directory exists; refusing credential overwrite')
    if not directory.parent.is_dir():
        raise BootstrapError('Output parent directory must already exist')
    staging = Path(tempfile.mkdtemp(prefix='.notepin-auth-', dir=directory.parent))
    try:
        os.chmod(staging, 0o700)
        for name, value in material.items():
            if Path(name).name != name:
                raise BootstrapError('Invalid private filename')
            fd = os.open(staging / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as f:
                f.write(value)
                f.flush()
                os.fsync(f.fileno())
        # New directory with all validated files. Never merge into existing auth.
        # mkdir is exclusive even if another process races with our initial check.
        directory.mkdir(mode=0o700)
        moved = []
        try:
            for name in material:
                os.rename(staging / name, directory / name)
                moved.append(name)
        except BaseException:
            for name in moved:
                (directory / name).unlink()
            directory.rmdir()
            raise
    finally:
        if staging.exists():
            shutil.rmtree(staging)


async def obtain(fetch, address, serial):
    devices = await fetch('GET', '/device/list', None)
    if serial not in device_serials(devices):
        raise BootstrapError('Target is not in the authenticated account device list')
    # Already-bound owner devices use the workspace member ID in the official
    # app's normal connection flow. bind-token is for onboarding and can reject
    # already-bound devices; do not unbind or apply for a new token.
    workspaces = await fetch('GET', '/workspaces/list', None)
    token = {'status': 0, 'data': {'token': owner_token(devices, workspaces, serial)}}
    # This evidenced endpoint can provision a key if an account has none. Only
    # call once, with the existing account; never generate a replacement locally.
    key = await fetch('POST', '/e2ee/gen_key', None)
    signature = await fetch('POST', '/device/sn-sign', {'sns': [serial]})
    return build_material(address, serial, key, signature, token)


async def browser_fetch(page, method, path, body):
    if not page.url.startswith(WEB_ORIGIN + '/'):
        raise BootstrapError('Waiting for the official Web origin')
    try:
        result = await page.evaluate('''async ({url,method,body}) => {
            const r = await fetch(url, {method,credentials:'include',
                headers:{'Content-Type':'application/json','X-Encrypt-Response':'1'},
                ...(body === null ? {} : {body:JSON.stringify(body)}),
                signal:AbortSignal.timeout(15000)});
            return {status:r.status,text:await r.text()};
        }''', {'url': API_ORIGIN + path, 'method': method, 'body': body})
    except Exception:
        raise BootstrapError('Official API network request failed') from None
    if result['status'] != 200:
        raise BootstrapError('Official API rejected the request')
    return decode_response(result['text'])


async def browser_main(args):
    try:
        from playwright.async_api import async_playwright
    except ImportError:
        raise BootstrapError('Install requirements-bootstrap.txt and Playwright Chromium') from None
    profile = Path(args.profile).expanduser().absolute()
    repo = Path(__file__).resolve().parent
    if profile == repo or repo in profile.parents:
        raise BootstrapError('Browser profile must be outside the project directory')
    if profile.is_symlink():
        raise BootstrapError('Browser profile must not be a symlink')
    if profile.exists() and stat.S_IMODE(profile.stat().st_mode) & 0o077:
        raise BootstrapError('Existing browser profile must have mode 0700')
    profile.mkdir(parents=True, mode=0o700, exist_ok=True)
    async with async_playwright() as pw:
        context = await pw.chromium.launch_persistent_context(
            str(profile), headless=False, executable_path=args.chromium,
            accept_downloads=False)
        try:
            page = await context.new_page()
            await page.goto(WEB_ORIGIN, wait_until='domcontentloaded')
            print('Log in on the official PLAUD page in the opened browser. Passwords are not read by this tool.')
            async def fetch(method, path, body):
                return await browser_fetch(page, method, path, body)
            end = time.monotonic() + args.login_timeout
            while True:
                try:
                    devices = await fetch('GET', '/device/list', None)
                    if args.serial not in device_serials(devices):
                        raise BootstrapError('Target is not in this account device list')
                    break
                except BootstrapError:
                    if time.monotonic() >= end:
                        raise BootstrapError('Login/target check timed out; no credentials saved') from None
                    await asyncio.sleep(3)
            material = await obtain(fetch, args.address.upper(), args.serial)
            save_material(args.output, material)
            print('Saved four private files (directory 0700, files 0600). Android import: private-android-bootstrap.json')
            print('Cloud acquisition complete. BLE authentication/header decryption still require device verification.')
        finally:
            await context.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--address', required=True, help='Exact owned NotePin Bluetooth MAC')
    parser.add_argument('--serial', required=True, help='Exact owned device serial')
    parser.add_argument('--output', required=True, help='New private output directory')
    parser.add_argument('--profile', default='~/.local/share/notepin-live/private-browser')
    parser.add_argument('--chromium', help='Optional system Chromium executable')
    parser.add_argument('--login-timeout', type=int, default=300)
    args = parser.parse_args()
    if not re.fullmatch(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', args.address) or not args.serial:
        parser.error('Exact MAC and serial required')
    if args.login_timeout < 1 or args.login_timeout > 1800:
        parser.error('Login timeout must be 1..1800 seconds')
    if Path(args.output).exists() or Path(args.output).is_symlink():
        parser.error('Output directory already exists')
    # Browser tracebacks may contain API bodies or session URLs. Never print them.
    try:
        asyncio.run(browser_main(args))
    except BootstrapError as e:
        parser.exit(1, f'Bootstrap stopped: {e}\n')
    except KeyboardInterrupt:
        parser.exit(1, 'Bootstrap interrupted.\n')
    except Exception:
        parser.exit(1, 'Bootstrap stopped: browser or local IO error; no secret details logged.\n')

if __name__ == '__main__':
    main()
