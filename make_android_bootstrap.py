"""Export only owner device bootstrap fields into an owner-readable Android import file."""
import argparse
import base64
import json
import os
import re
from pathlib import Path
from cryptography.hazmat.primitives.serialization import load_pem_private_key


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auth-dir', required=True)
    parser.add_argument('--address', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, help='New private JSON file; never commit/upload')
    args = parser.parse_args()
    address = args.address.upper()
    if not re.fullmatch(r'(?:[0-9A-F]{2}:){5}[0-9A-F]{2}', address) or not args.serial:
        parser.error('Explicit exact device MAC and serial required')
    root = Path(args.auth_dir)
    auth = json.loads((root / 'private-research-auth.json').read_text())
    if auth['sign']['status'] != 0:
        raise ValueError('Failed signature response')
    signature = auth['sign']['data']['signature'][args.serial]
    if len(base64.b64decode(signature, validate=True)) != 256:
        raise ValueError('Signature length mismatch')
    pem = (root / 'private-account-key.pem').read_text()
    load_pem_private_key(pem.encode(), None)
    handshake = (root / 'private-owner-handshake.bin').read_bytes()
    if handshake[:3] != b'\x01\x01\x00':
        raise ValueError('Not the observed owner handshake')
    payload = dict(address=address, serial=args.serial, signature=signature,
                   privateKeyPem=pem, ownerHandshake=base64.b64encode(handshake).decode(),
                   streamEnabled=False, streamVad=False)
    fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as out:
        json.dump(payload, out)
        out.write('\n')
    print('Owner-only bootstrap file created. WebSocket disabled. No credential values logged.')


if __name__ == '__main__':
    main()
