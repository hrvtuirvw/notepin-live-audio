"""Durable PCM WebSocket inbox; ACK means SQLite commit, not transcription completion."""
import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import sqlite3
import ssl
from pathlib import Path
from websockets.asyncio.server import serve


def validate(m):
    if m.get('v') != 1 or m.get('type') != 'audio' or m.get('mode') not in ('live', 'history'):
        raise ValueError('Unsupported message')
    for key in ('source', 'recording_key'):
        if not isinstance(m.get(key), str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,96}', m[key]):
            raise ValueError('Invalid identity')
    for key, low, high in [('recording_id', 1, 0xffffffff), ('start_sample', 0, 2**53-1)]:
        if type(m.get(key)) is not int or not low <= m[key] <= high:
            raise ValueError('Invalid position')
    if m.get('rate') != 16000 or m.get('channels') != 1 or m.get('format') != 's16le':
        raise ValueError('Unsupported PCM format')
    if 'vad' in m:
        if (m['vad'] != 'webrtc-normal' or type(m.get('utterance_end')) is not bool
                or type(m.get('utterance_start_sample')) is not int
                or not 0 <= m['utterance_start_sample'] <= m['start_sample']):
            raise ValueError('Invalid VAD boundary')
    pcm = base64.b64decode(m['pcm'], validate=True)
    if not 0 < len(pcm) <= 32000 or len(pcm) % 640:
        raise ValueError('Expected up to one second of complete 20ms PCM frames')
    digest = hashlib.sha256(pcm).hexdigest()
    if not hmac.compare_digest(digest, m.get('sha256', '')):
        raise ValueError('Checksum mismatch')
    expected = f"{m['source']}:{m['recording_id']}:{m['recording_key']}:{m['start_sample']}"
    if m.get('id') != expected:
        raise ValueError('Chunk ID mismatch')
    return pcm, digest


class Inbox:
    def __init__(self, directory):
        root = Path(directory)
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.db = sqlite3.connect(root / 'inbox.sqlite3')
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS chunks (id TEXT PRIMARY KEY, source TEXT, recording_id INTEGER, recording_key TEXT, start_sample INTEGER, samples INTEGER, mode TEXT, sha256 TEXT, metadata TEXT, pcm BLOB, received_at TEXT DEFAULT CURRENT_TIMESTAMP, consumed INTEGER DEFAULT 0)')
        self.db.commit()

    def accept(self, m):
        pcm, digest = validate(m)
        old = self.db.execute('SELECT sha256,samples FROM chunks WHERE id=?', (m['id'],)).fetchone()
        if old:
            if old != (digest, len(pcm)//2):
                raise ValueError('Conflicting replay; existing audio retained')
        else:
            # No overlapping ranges even when replay chunk boundaries change.
            overlap = self.db.execute('SELECT id FROM chunks WHERE source=? AND recording_id=? AND recording_key=? AND start_sample < ? AND start_sample+samples > ? LIMIT 1',
                (m['source'], m['recording_id'], m['recording_key'], m['start_sample']+len(pcm)//2, m['start_sample'])).fetchone()
            if overlap:
                raise ValueError('Overlapping range; use stable one-second chunk boundaries')
            meta = {k:v for k,v in m.items() if k != 'pcm'}
            with self.db:
                self.db.execute('INSERT INTO chunks(id,source,recording_id,recording_key,start_sample,samples,mode,sha256,metadata,pcm) VALUES(?,?,?,?,?,?,?,?,?,?)',
                    (m['id'], m['source'], m['recording_id'], m['recording_key'], m['start_sample'], len(pcm)//2, m['mode'], digest, json.dumps(meta), pcm))
        return {'v':1, 'type':'ack', 'id':m['id'], 'sha256':digest, 'duplicate':bool(old)}


def handler(inbox, token):
    async def receive(ws):
        auth = ws.request.headers.get('Authorization', '')
        if ws.request.path != '/audio' or not hmac.compare_digest(auth, 'Bearer ' + token):
            await ws.close(code=1008, reason='Unauthorized')
            return
        try:
            async for raw in ws:
                try:
                    ack = inbox.accept(json.loads(raw))
                except (ValueError, KeyError, TypeError):
                    await ws.close(code=1008, reason='Invalid chunk or conflicting replay')
                    return
                await ws.send(json.dumps(ack))
        except Exception:
            # Disconnect leaves committed inbox intact, allowing idempotent replay.
            return
    return receive


async def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=8765)
    p.add_argument('--directory', default='stream-inbox')
    p.add_argument('--cert'); p.add_argument('--key')
    a=p.parse_args()
    token=os.environ.get('NOTEPIN_STREAM_TOKEN','')
    if len(token)<24: p.error('Set NOTEPIN_STREAM_TOKEN to a random token of at least 24 characters')
    if bool(a.cert)!=bool(a.key): p.error('Supply both TLS cert and key')
    ctx=None
    if a.cert:
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(a.cert,a.key)
    os.umask(0o077)
    inbox=Inbox(a.directory)
    async with serve(handler(inbox,token), a.host,a.port, ssl=ctx, max_size=65536, compression=None, ping_interval=20):
        print(f'Receiver ready on {a.host}:{a.port}/audio; ACK after durable commit',flush=True)
        await asyncio.Future()

if __name__=='__main__':
    asyncio.run(main())
