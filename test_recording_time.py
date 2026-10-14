import unittest
from recording_time import recording_times

class RecordingTimes(unittest.TestCase):
    def test_timezone_and_unsigned_boundary(self):
        self.assertEqual(recording_times(1791244263)['recording_start_jst'], '2026-10-06T08:51:03+09:00')
        self.assertEqual(recording_times(1791262277)['recording_start_utc'], '2026-10-06T04:51:17+00:00')
        self.assertEqual(recording_times(0xffffffff)['recording_start_jst'], '2106-02-07T15:28:15+09:00')
        for invalid in [-1, 0, 0x100000000, True]:
            with self.assertRaises(ValueError): recording_times(invalid)
