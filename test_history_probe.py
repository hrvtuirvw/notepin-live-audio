import struct
import unittest
from history_probe import recording_target


class HistoryTargetTests(unittest.TestCase):
    def state(self, active):
        state = bytearray(18)
        state[:3] = b'\x01\x03\x00'
        state[10] = int(active)
        struct.pack_into('<I', state, 11, 0x12345678)
        return bytes(state)

    def test_known_archive_can_be_read_while_device_is_idle(self):
        self.assertEqual(recording_target(self.state(False), 12345), (12345, None))

    def test_known_archive_can_be_read_during_a_different_recording(self):
        self.assertEqual(recording_target(self.state(True), 12345), (12345, 0x12345678))

    def test_active_id_cannot_be_claimed_as_history(self):
        with self.assertRaises(ValueError):
            recording_target(self.state(True), 0x12345678)

    def test_live_capture_requires_an_active_recording(self):
        self.assertEqual(recording_target(self.state(True)), (0x12345678, 0x12345678))
        with self.assertRaises(ValueError):
            recording_target(self.state(False))

    def test_invalid_state_and_ids_rejected(self):
        for state in (b'', b'\x01\x1a\x00' + bytes(15)):
            with self.assertRaises(ValueError):
                recording_target(state, 12345)
        for recording_id in (0, -1, 0x100000000):
            with self.assertRaises(ValueError):
                recording_target(self.state(False), recording_id)


if __name__ == '__main__':
    unittest.main()
