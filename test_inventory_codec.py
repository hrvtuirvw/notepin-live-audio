import struct
import unittest
from inventory_codec import Inventory, decode_page, entry_stride


def page(uid=123, total=2, index=0, ids=(1,), stride=10):
    return b'\x01\x1a\x00' + struct.pack('<IHH', uid, total, index) + b''.join(
        struct.pack('<II', n, 1312) + bytes(stride - 8) for n in ids)


class InventoryTests(unittest.TestCase):
    def test_sdk_version_branches(self):
        self.assertEqual(8, entry_stride(1))
        for v in range(2, 7): self.assertEqual(9, entry_stride(v))
        self.assertEqual(10, entry_stride(20))

    def test_paginated_total_not_page_count(self):
        a = Inventory(123, protocol_version=20)
        a.accept(page()); self.assertFalse(a.complete)
        a.accept(page(index=1, ids=(0xffffffff,))); self.assertTrue(a.complete)
        self.assertEqual(0xffffffff, a.entries[-1].recording_id)

    def test_wrong_uid_does_not_change_state(self):
        a = Inventory(123, protocol_version=20)
        with self.assertRaises(ValueError): a.accept(page(uid=124))
        self.assertIsNone(a.total); self.assertEqual([], a.entries)

    def test_no_hole(self):
        a = Inventory(123, protocol_version=20)
        with self.assertRaises(ValueError): a.accept(page(index=1))

    def test_idempotent_duplicate_and_conflict(self):
        a = Inventory(123, protocol_version=20); a.accept(page())
        self.assertFalse(a.accept(page()))
        with self.assertRaises(ValueError): a.accept(page(ids=(2,)))

    def test_changed_total_rejected(self):
        a = Inventory(123, protocol_version=20); a.accept(page())
        with self.assertRaises(ValueError): a.accept(page(total=3, index=1, ids=(2,)))

    def test_cross_page_duplicate_rejected(self):
        a = Inventory(123, protocol_version=20); a.accept(page())
        with self.assertRaises(ValueError): a.accept(page(index=1))

    def test_empty_inventory(self):
        a = Inventory(123, protocol_version=20)
        a.accept(page(total=0, ids=())); self.assertTrue(a.complete)

    def test_malformed_lengths_and_bounds(self):
        for b in (b'', page()[:-1], page(total=0), page(index=2)):
            with self.assertRaises(ValueError): decode_page(b, protocol_version=20)

    def test_explicit_version_and_stride(self):
        for v in (0, -1, True, None):
            with self.assertRaises(ValueError): entry_stride(v)
        with self.assertRaises(ValueError): decode_page(page(stride=8), protocol_version=20)


if __name__ == '__main__': unittest.main()
