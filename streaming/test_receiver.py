import asyncio,base64,hashlib,json,sqlite3,tempfile,unittest
from pathlib import Path
from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed
from receiver import Inbox,handler
TOKEN='test-only-token-for-local-check-0000'

def chunk(start=0, mode='live'):
    pcm=b'\x01\x00'*16000
    return dict(v=1,type='audio',source='owner01',recording_key='key01',recording_id=1791262277,
        start_sample=start,rate=16000,channels=1,format='s16le',mode=mode,
        id=f'owner01:1791262277:key01:{start}',pcm=base64.b64encode(pcm).decode(),sha256=hashlib.sha256(pcm).hexdigest())

class ReceiverTests(unittest.IsolatedAsyncioTestCase):
    async def test_ack_loss_disconnect_restart_and_replay(self):
        with tempfile.TemporaryDirectory() as root:
            inbox=Inbox(root)
            async def drop_ack(ws):
                inbox.accept(json.loads(await ws.recv()))
                await ws.close()
            async with serve(drop_ack,'127.0.0.1',0) as server:
                port=server.sockets[0].getsockname()[1]
                async with connect(f'ws://127.0.0.1:{port}/audio') as ws:
                    await ws.send(json.dumps(chunk()))
                    with self.assertRaises(ConnectionClosed): await ws.recv()
            inbox.db.close()
            inbox=Inbox(root)  # restart retains committed audio
            async with serve(handler(inbox,TOKEN),'127.0.0.1',0) as server:
                port=server.sockets[0].getsockname()[1]
                async with connect(f'ws://127.0.0.1:{port}/audio',additional_headers={'Authorization':'Bearer '+TOKEN}) as ws:
                    await ws.send(json.dumps(chunk()))
                    ack=json.loads(await ws.recv());self.assertTrue(ack['duplicate'])
                    self.assertEqual(ack['sha256'],chunk()['sha256'])
                    await ws.send(json.dumps(chunk(16000)))
                    self.assertFalse(json.loads(await ws.recv())['duplicate'])
                self.assertEqual(inbox.db.execute('SELECT count(*),sum(samples) FROM chunks').fetchone(),(2,32000))
            inbox.db.close()

    async def test_vad_gaps_and_end_metadata_survive_replay(self):
        with tempfile.TemporaryDirectory() as root:
            inbox=Inbox(root)
            for start in (6400, 64000):
                m=chunk(start);m.update(vad='webrtc-normal',utterance_start_sample=start,utterance_end=True)
                self.assertFalse(inbox.accept(m)['duplicate'])
                self.assertTrue(inbox.accept(m)['duplicate'])
            rows=inbox.db.execute('SELECT start_sample,metadata FROM chunks ORDER BY start_sample').fetchall()
            self.assertEqual([r[0] for r in rows],[6400,64000])
            self.assertTrue(all(json.loads(r[1])['utterance_end'] for r in rows))
            m=chunk(96000);m.update(vad='webrtc-normal',utterance_start_sample=96001,utterance_end=True)
            with self.assertRaises(ValueError): inbox.accept(m)
            inbox.db.close()

    async def test_auth_and_invalid_checksum_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            inbox=Inbox(root)
            async with serve(handler(inbox,TOKEN),'127.0.0.1',0) as server:
                port=server.sockets[0].getsockname()[1]
                async with connect(f'ws://127.0.0.1:{port}/audio') as ws:
                    with self.assertRaises(ConnectionClosed): await ws.recv()
                async with connect(f'ws://127.0.0.1:{port}/audio',additional_headers={'Authorization':'Bearer '+TOKEN}) as ws:
                    m=chunk();m['sha256']='0'*64
                    await ws.send(json.dumps(m))
                    with self.assertRaises(ConnectionClosed): await ws.recv()
                self.assertEqual(inbox.db.execute('SELECT count(*) FROM chunks').fetchone()[0],0)
            inbox.db.close()

    async def test_conflicting_replay_overlap_and_oversize(self):
        with tempfile.TemporaryDirectory() as root:
            inbox=Inbox(root);inbox.accept(chunk())
            m=chunk();pcm=b'\x02\x00'*16000;m['pcm']=base64.b64encode(pcm).decode();m['sha256']=hashlib.sha256(pcm).hexdigest()
            with self.assertRaises(ValueError): inbox.accept(m)
            with self.assertRaises(ValueError): inbox.accept(chunk(320))
            m=chunk(16000);pcm=b'\x00'*32640;m['pcm']=base64.b64encode(pcm).decode();m['sha256']=hashlib.sha256(pcm).hexdigest()
            with self.assertRaises(ValueError): inbox.accept(m)
            self.assertEqual(inbox.db.execute('SELECT count(*) FROM chunks').fetchone()[0],1)
            inbox.db.close()
