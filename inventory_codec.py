"""Offline BLE command-26 inventory decoder. No transport or device mutations.

Layout derived from APK 3.28.0 ki.x and pi.n, not a firmware guarantee.
Input is the decrypted command body, WITHOUT its four-byte request number.
"""
from dataclasses import dataclass
import struct


@dataclass(frozen=True)
class Entry:
    recording_id: int
    file_size: int
    extra: bytes


@dataclass(frozen=True)
class Page:
    request_uid: int
    total: int
    index: int
    entries: tuple[Entry, ...]


def entry_stride(protocol_version):
    if not isinstance(protocol_version, int) or isinstance(protocol_version, bool) or not 1 <= protocol_version <= 65535:
        raise ValueError('An explicit positive handshake protocol version is required')
    # Actual packed-switch in ki.x.b: 1 -> 8, 2..6 -> 9, default -> 10.
    return 8 if protocol_version == 1 else 9 if protocol_version <= 6 else 10


def decode_page(body, *, protocol_version):
    if len(body) < 11 or body[:3] != b'\x01\x1a\x00':
        raise ValueError('Short/wrong inventory body')
    uid, total, index = struct.unpack_from('<IHH', body, 3)
    stride = entry_stride(protocol_version)
    data = body[11:]
    if len(data) % stride:
        raise ValueError('Incomplete inventory entry')
    count = len(data) // stride
    if index > total or index + count > total or (not count and total != 0):
        raise ValueError('Inconsistent inventory bounds')
    entries = tuple(Entry(*struct.unpack_from('<II', data, p), data[p + 8:p + stride])
                    for p in range(0, len(data), stride))
    if len({e.recording_id for e in entries}) != count:
        raise ValueError('Duplicate recording IDs in page')
    return Page(uid, total, index, entries)


class Inventory:
    """Ordered, request-scoped assembly; no holes, no guessed record stride."""
    def __init__(self, request_uid, *, protocol_version):
        if not isinstance(request_uid, int) or not 0 <= request_uid <= 0xffffffff:
            raise ValueError('Invalid request UID')
        entry_stride(protocol_version)
        self.request_uid = request_uid
        self.protocol_version = protocol_version
        self.total = None
        self.entries = []
        self._pages = {}

    @property
    def complete(self):
        return self.total is not None and len(self.entries) == self.total

    def accept(self, body):
        page = decode_page(body, protocol_version=self.protocol_version)
        if page.request_uid != self.request_uid:
            raise ValueError('Response belongs to another inventory request')
        if self.total is not None and page.total != self.total:
            raise ValueError('Total changed during inventory')
        if page.index in self._pages:
            if self._pages[page.index] != page:
                raise ValueError('Conflicting duplicate page')
            return False
        if page.index != len(self.entries):
            raise ValueError('Missing/out-of-order inventory page')
        seen = {e.recording_id for e in self.entries}
        if any(e.recording_id in seen for e in page.entries):
            raise ValueError('Duplicate recording ID across pages')
        self.total = page.total
        self.entries.extend(page.entries)
        self._pages[page.index] = page
        return True
