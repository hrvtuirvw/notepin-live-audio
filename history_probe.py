"""Owner-NotePin authentication, state and direct recording-time byte collection.

Does not pair, register, change recording, delete, or modify settings.
"""
import asyncio
import argparse
import base64
import json
import struct
import os
import time
from pathlib import Path
from recording_time import recording_times
from inventory_codec import Inventory, entry_stride
from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import load_pem_private_key
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

ADDRESS = os.environ.get('NOTEPIN_ADDRESS', '').upper()
SERIAL = os.environ.get('NOTEPIN_SERIAL', '')
ADAPTER = os.environ.get('NOTEPIN_ADAPTER', 'hci0')
SERVICE = '00001910-0000-1000-8000-00805f9b34fb'
WRITE = '00002bb1-0000-1000-8000-00805f9b34fb'
NOTIFY = '00002bb0-0000-1000-8000-00805f9b34fb'


def checked_signature(auth, serial=SERIAL):
    if serial != SERIAL or auth['sign']['status'] != 0:
        raise ValueError('Wrong owner target or failed signature response')
    sig = base64.b64decode(auth['sign']['data']['signature'][serial], validate=True)
    if len(sig) != 256:
        raise ValueError('Wrong signature length')
    return sig


def file_packet(plain, session_id, start):
    if len(plain) < 10 or plain[0] != 2:
        return None
    sid, offset = struct.unpack_from('<II', plain, 1)
    if sid != session_id or plain[9] != len(plain) - 10 or offset < start:
        return None
    return offset, plain[10:]


def recording_target(state, historical_id=None):
    if len(state) < 18 or state[:3] != b'\x01\x03\x00':
        raise ValueError('Short/wrong recording state')
    active_id = struct.unpack_from('<I', state, 11)[0] if state[10] == 1 else None
    if historical_id is None:
        if active_id is None:
            raise ValueError('Not a proven active recording')
        return active_id, active_id
    if not 0 < historical_id <= 0xffffffff:
        raise ValueError('History ID must be a positive uint32')
    if historical_id == active_id:
        raise ValueError('History target equals active recording')
    return historical_id, active_id


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--list', action='store_true')
    parser.add_argument('--finish-marker', help='Local file existence requests graceful early completion')
    parser.add_argument('--history', type=int)
    parser.add_argument('--start', type=int)
    parser.add_argument('--seconds', type=float, default=12, help='0: continue until interrupted')
    parser.add_argument('--output')
    parser.add_argument('--session', type=int, help='Refuse resume against a different recording')
    args = parser.parse_args()
    if args.history is not None and not 0 < args.history <= 0xffffffff:
        parser.error('History ID must be a positive uint32')
    if args.history is not None and (args.start is None or args.seconds <= 0):
        parser.error('History probes require an explicit start and positive bounded duration')
    if args.seconds < 0:
        parser.error('Seconds cannot be negative')
    if args.start is not None and (args.start < 512 or (args.start - 512) % 80 or args.start > 0xffffffff or not args.output):
        parser.error('Aligned uint32 payload offset and new output required')
    if not ADDRESS or not SERIAL:
        parser.error('Set NOTEPIN_ADDRESS and NOTEPIN_SERIAL to the exact owner device')
    root = Path(os.environ.get('NOTEPIN_AUTH_DIR', str(Path(__file__).parent / 'private-auth')))
    auth = json.loads((root / 'private-research-auth.json').read_text())
    signature = checked_signature(auth)
    key = load_pem_private_key((root / 'private-account-key.pem').read_bytes(), None)
    device = await BleakScanner.find_device_by_address(ADDRESS, timeout=12, adapter=ADAPTER)
    if device is None or device.address.upper() != ADDRESS:
        raise RuntimeError('Exact owner target not advertised; no substitution')
    queue = asyncio.Queue()
    async with BleakClient(device, bluez={'adapter': ADAPTER}, timeout=15, pair=False) as client:
        service = client.services.get_service(SERVICE)
        if service is None:
            raise RuntimeError('Wrong service')
        wc, nc = service.get_characteristic(WRITE), service.get_characteristic(NOTIFY)
        if wc is None or nc is None or 'write-without-response' not in wc.properties or 'notify' not in nc.properties:
            raise RuntimeError('Wrong characteristic roles')
        await client.start_notify(nc, lambda _, b: queue.put_nowait(bytes(b)))
        chunks = [signature[i:i+100] for i in range(0, len(signature), 100)]
        for i, chunk in enumerate(chunks):
            await client.write_gatt_char(wc, b'\x10\xfe' + bytes([len(chunks), i]) + chunk, response=False)
            await asyncio.sleep(.12)
        parts = {}
        async with asyncio.timeout(12):
            while len(parts) < 3:
                b = await queue.get()
                if b[:2] == b'\x12\xfe' and len(b) >= 4 and b[2] == 3 and b[3] < 3:
                    parts[b[3]] = b[4:]
        session = key.decrypt(b''.join(parts[i] for i in range(3)), padding.PKCS1v15())
        if len(session) != 80:
            raise RuntimeError('Wrong session length')
        cipher, nonce, ad = ChaCha20Poly1305(session[:32]), session[32:44], session[44:56]
        if cipher.decrypt(nonce, session[56:], ad) != b'PLAUD.AI':
            raise RuntimeError('Handshake authentication mismatch')
        print(json.dumps({'direct_signature_auth': True}), flush=True)
        # Existing-owner handshake only, isolated from all other captured commands.
        handshake = (root / 'private-owner-handshake.bin').read_bytes()
        if handshake[:3] != b'\x01\x01\x00':
            raise RuntimeError('Not an owner handshake')
        await client.write_gatt_char(wc, cipher.encrypt(nonce, struct.pack('<I', 1) + handshake, ad), response=False)
        async with asyncio.timeout(8):
            while True:
                b = await queue.get()
                try:
                    d = cipher.decrypt(nonce, b, ad)
                except Exception:
                    continue
                if d[4:7] == b'\x01\x01\x00':
                    owner_reply = d[4:]
                    if len(owner_reply) < 9 or owner_reply[3] != 0:
                        raise RuntimeError('Short/refused owner handshake')
                    protocol_version = struct.unpack_from('<H', owner_reply, 4)[0]
                    entry_stride(protocol_version)
                    print(json.dumps({'owner_handshake_reply': True, 'protocol_version': protocol_version}), flush=True)
                    break
        await client.write_gatt_char(wc, cipher.encrypt(nonce, struct.pack('<I', 2) + b'\x01\x03\x00', ad), response=False)
        async with asyncio.timeout(8):
            while True:
                b = await queue.get()
                try:
                    d = cipher.decrypt(nonce, b, ad)
                except Exception:
                    continue
                if d[4:7] == b'\x01\x03\x00':
                    print(json.dumps({'direct_status_reply': True, 'payload_hex': d[4:].hex()}), flush=True)
                    body = d[4:]
                    if len(body) >= 18 and body[10] == 1:
                        active_id = struct.unpack_from('<I', body, 11)[0]
                        print(json.dumps({'active_recording_id': active_id, **recording_times(active_id)}), flush=True)
                    break
        if args.list:
            uid = int(time.time())
            inventory = Inventory(uid, protocol_version=protocol_version)
            req = b'\x01\x1a\x00' + struct.pack('<II', uid, 0) + b'\x00'
            await client.write_gatt_char(wc, cipher.encrypt(nonce, struct.pack('<I', 3) + req, ad), response=False)
            end = time.monotonic() + 12
            while time.monotonic() < end and not inventory.complete:
                try:
                    b = await asyncio.wait_for(queue.get(), 1)
                    plain = cipher.decrypt(nonce, b, ad)[4:]
                except TimeoutError:
                    continue
                if plain[:3] != b'\x01\x1a\x00':
                    continue
                inventory.accept(plain)
            print(json.dumps({'request_complete': inventory.complete, 'total': inventory.total,
                              'recordings': [{'recording_id': e.recording_id, 'file_size': e.file_size}
                                             for e in inventory.entries]}), flush=True)
            return
        if args.start is None:
            return
        session_id, active_session = recording_target(d[4:], args.history)
        print(json.dumps({'active_session': active_session, 'history_target': args.history}), flush=True)
        if args.session is not None and session_id != args.session:
            raise RuntimeError('Recording changed; refusing to reuse old cursor')
        print(json.dumps({'recording_id': session_id, **recording_times(session_id)}), flush=True)
        # Obtain the recording header directly, not from the phone cache.
        header_request = b'\x01\x1c\x00' + struct.pack('<III', session_id, 0, 512)
        await client.write_gatt_char(wc, cipher.encrypt(nonce, struct.pack('<I', 3) + header_request, ad), response=False)
        header_parts = {}
        async with asyncio.timeout(10):
            while True:
                b = await queue.get()
                try:
                    plain = cipher.decrypt(nonce, b, ad)[4:]
                except Exception:
                    continue
                if len(plain) >= 10 and plain[0] == 2:
                    sid, offset = struct.unpack_from('<II', plain, 1)
                    if sid == session_id and len(plain) - 10 == plain[9] and offset < 640:
                        header_parts[offset] = plain[10:]
                    header = bytearray()
                    while len(header) in header_parts:
                        header.extend(header_parts[len(header)])
                    if len(header) >= 512:
                        break
        from decode_cache import content_key
        content_key(bytes(header[:512]), (root / 'private-account-key.pem').read_bytes())
        header_fd = os.open(args.output + '.header.bin', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(header_fd, 'wb') as f:
            f.write(header[:512])
        # Read-only range request, wire format verified in authenticated capture.
        request = b'\x01\x1c\x00' + struct.pack('<III', session_id, args.start, 0)
        fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        ranges = {}
        eof = False
        updates = []
        started = time.monotonic()
        with os.fdopen(fd, 'wb') as out:
            await client.write_gatt_char(wc, cipher.encrypt(nonce, struct.pack('<I', 4) + request, ad), response=False)
            cursor = args.start
            last_update = 0
            while args.seconds == 0 or time.monotonic() - started < args.seconds:
                if args.finish_marker and Path(args.finish_marker).exists():
                    break
                try:
                    b = await asyncio.wait_for(queue.get(), 1)
                except TimeoutError:
                    continue
                try:
                    plain = cipher.decrypt(nonce, b, ad)[4:]
                except Exception:
                    continue
                if plain[:1] != b'\x02' or (len(plain) >= 9 and struct.unpack_from('<I', plain, 5)[0] == 0xffffffff):
                    print(json.dumps({'transfer_reply': plain[:32].hex(), 'length': len(plain)}), flush=True)
                if len(plain) >= 9 and plain[0] == 2 and struct.unpack_from('<II', plain, 1) == (session_id, 0xffffffff) and cursor > args.start:
                    eof = True
                    print(json.dumps({'historical_eof': True, 'cursor': cursor}), flush=True)
                    break
                packet = file_packet(plain, session_id, args.start)
                if packet is not None:
                    offset, chunk = packet
                    if not chunk or offset < cursor:
                        continue
                    ranges[offset] = chunk
                    while cursor in ranges:
                        chunk = ranges.pop(cursor)
                        out.write(chunk)
                        cursor += len(chunk)
                    elapsed = time.monotonic() - started
                    if elapsed - last_update >= 1:
                        out.flush()
                        updates.append({'elapsed': round(elapsed, 3), 'end': cursor})
                        last_update = elapsed
                        checkpoint = {'session_id': session_id, **recording_times(session_id), 'remote_start': args.start, 'remote_end': cursor,
                                      'copied_bytes': cursor - args.start, 'source': 'DIRECT_BLE_NO_OFFICIAL_APP'}
                        temp = Path(args.output + '.json.tmp')
                        temp.write_text(json.dumps(checkpoint) + '\n')
                        temp.replace(args.output + '.json')
                        print(json.dumps(checkpoint), flush=True)
            out.flush()
        result = {'session_id': session_id, **recording_times(session_id), 'remote_start': args.start, 'remote_end': cursor,
                  'copied_bytes': cursor - args.start, 'seconds': args.seconds,
                  'source': 'DIRECT_BLE_NO_OFFICIAL_APP', 'pending_gaps': len(ranges), 'eof_observed': eof, 'updates': updates}
        Path(args.output + '.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({k: v for k, v in result.items() if k != 'updates'}), flush=True)
        await client.write_gatt_char(wc, cipher.encrypt(nonce, struct.pack('<I', 5) + b'\x01\x03\x00', ad), response=False)
        async with asyncio.timeout(8):
            while True:
                b = await queue.get()
                plain = cipher.decrypt(nonce, b, ad)[4:]
                if plain[:3] == b'\x01\x03\x00':
                    after_sid = struct.unpack_from('<I', plain, 11)[0]
                    print(json.dumps({'after_status': plain.hex(), 'recording_continues': plain[10] == 1 and after_sid == active_session}), flush=True)
                    break


if __name__ == '__main__':
    asyncio.run(main())
