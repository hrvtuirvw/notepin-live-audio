"""Offline target ownership and observed wire-boundary checks."""
import base64
import struct
import unittest
from direct_probe import checked_signature, file_packet, SERIAL


class Boundaries(unittest.TestCase):
    def test_exact_owner_signature_only(self):
        auth = {'sign': {'status': 0, 'data': {'signature': {
            SERIAL: base64.b64encode(bytes(256)).decode()}}}}
        self.assertEqual(len(checked_signature(auth)), 256)
        with self.assertRaises(ValueError):
            checked_signature(auth, 'unrelated-peer')
        auth['sign']['status'] = -1
        with self.assertRaises(ValueError):
            checked_signature(auth)

    def test_packet_requires_full_owner_session_and_exact_length(self):
        data = b'\x02' + struct.pack('<II', 123456789, 512) + b'\xa0' + bytes(160)
        self.assertEqual(file_packet(data, 123456789, 512), (512, bytes(160)))
        self.assertIsNone(file_packet(data, 123, 512))
        self.assertIsNone(file_packet(data, 123456789, 672))
        self.assertIsNone(file_packet(data[:-1], 123456789, 512))
        self.assertIsNone(file_packet(data[:9], 123456789, 512))
        self.assertIsNone(file_packet(b'\x03' + data[1:], 123456789, 512))

    def test_evidenced_request_width_and_endianness(self):
        request = b'\x01\x1c\x00' + struct.pack('<III', 123456789, 0, 640)
        self.assertEqual(request.hex(), '011c0015cd5b070000000080020000')
        with self.assertRaises(struct.error):
            struct.pack('<III', 123456789, 0x100000000, 0)


if __name__ == '__main__':
    unittest.main()
