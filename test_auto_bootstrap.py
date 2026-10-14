import base64
import json
import stat
import tempfile
import unittest
from pathlib import Path
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption
from auto_bootstrap import (BootstrapError, RESPONSE_ENCODING_KEY, build_material,
                            decode_response, obtain, owner_handshake, save_material)

class BootstrapTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        pem = key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
        self.key = {'status':0,'data':{'private_key_base64':base64.b64encode(pem).decode()}}
        self.sign = {'status':0,'data':{'signature':{'synthetic-serial':base64.b64encode(bytes(256)).decode()}}}
        self.token = {'status':0,'data':{'token':'a'*32}}
        self.address = 'AA:BB:CC:DD:EE:FF'

    def material(self):
        return build_material(self.address,'synthetic-serial',self.key,self.sign,self.token)

    def test_integrity_and_status_fail_closed(self):
        payload = json.dumps(self.token).encode()
        encrypted = Fernet(RESPONSE_ENCODING_KEY).encrypt(payload)
        self.assertEqual(decode_response(encrypted.decode()),self.token)
        corrupt = bytearray(base64.urlsafe_b64decode(encrypted));corrupt[-1] ^= 1
        with self.assertRaises(BootstrapError):decode_response(base64.urlsafe_b64encode(corrupt).decode())
        with self.assertRaises(BootstrapError):decode_response('{"status":1,"msg":"private error"}')

    def test_handshake_and_bootstrap_fields(self):
        self.assertEqual(owner_handshake('a'*32),bytes.fromhex('010100020000')+b'a'*32)
        payload = json.loads(self.material()['private-android-bootstrap.json'])
        self.assertFalse(payload['streamEnabled'])
        self.assertFalse(payload['streamVad'])
        self.assertEqual(set(payload), {'address', 'serial', 'signature', 'privateKeyPem',
                                        'ownerHandshake', 'streamEnabled', 'streamVad'})
        self.assertEqual(base64.b64decode(payload['ownerHandshake']),owner_handshake('a'*32))
        for invalid in ['', 'g'*32, 'a'*31, 'a'*33]:
            with self.assertRaises(BootstrapError):owner_handshake(invalid)

    def test_invalid_key_and_target_signature(self):
        with self.assertRaises(BootstrapError):
            build_material(self.address,'other-serial',self.key,self.sign,self.token)
        key = {'status':0,'data':{'private_key_base64':base64.b64encode(b'invalid').decode()}}
        with self.assertRaises(BootstrapError):
            build_material(self.address,'synthetic-serial',key,self.sign,self.token)

    def test_private_permissions_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)/'auth';save_material(out,self.material())
            self.assertEqual(stat.S_IMODE(out.stat().st_mode),0o700)
            for p in out.iterdir():self.assertEqual(stat.S_IMODE(p.stat().st_mode),0o600)
            before={p.name:p.read_bytes() for p in out.iterdir()}
            with self.assertRaises(BootstrapError):save_material(out,self.material())
            self.assertEqual(before,{p.name:p.read_bytes() for p in out.iterdir()})

    async def test_exact_target_and_request_sequence(self):
        calls=[]
        async def fetch(method,path,body):
            calls.append((method,path,body))
            if path=='/device/list':return {'status':0,'data':[{'sn':'synthetic-serial','workspace_id':'synthetic-workspace','owner_member_id':'a'*32}]}
            if path=='/workspaces/list':return {'status':0,'data':[{'workspace_id':'synthetic-workspace','member_id':'a'*32,'workspace_type':0}]}
            if path=='/e2ee/gen_key':return self.key
            if path=='/device/sn-sign':return self.sign
            self.fail('Unexpected endpoint')
        result=await obtain(fetch,self.address,'synthetic-serial')
        self.assertEqual(result,self.material())
        self.assertEqual([x[0] for x in calls],['GET','GET','POST','POST'])
        self.assertEqual(calls[-1][2],{'sns':['synthetic-serial']})
        calls.clear()
        with self.assertRaises(BootstrapError):await obtain(fetch,self.address,'unowned-serial')
        self.assertEqual(len(calls),1) # no token/key/sign operation for another target

    async def test_unowned_workspace_stops_before_key_provisioning(self):
        calls=[]
        async def fetch(method,path,body):
            calls.append(path)
            if path=='/device/list':return {'status':0,'data':[{'sn':'synthetic-serial','workspace_id':'synthetic-workspace','owner_member_id':'a'*32}]}
            return {'status':0,'data':[{'workspace_id':'synthetic-workspace','member_id':'b'*32,'workspace_type':0}]}
        with self.assertRaises(BootstrapError):await obtain(fetch,self.address,'synthetic-serial')
        self.assertEqual(len(calls),2)

if __name__=='__main__':unittest.main()
