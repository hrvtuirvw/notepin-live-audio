"""Decode an acquired, aligned NotePin cache range; never fills sparse gaps."""
import argparse
import ctypes
import json
import os
import struct
import wave
import time
from pathlib import Path
from cryptography.hazmat.primitives.serialization import load_pem_private_key
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms


def content_key(header, pem):
    if len(header) != 512 or header[:8] != b'PLAUD.AI':
        raise ValueError('Expected 512-byte PLAUD header')
    n = load_pem_private_key(pem, None).private_numbers()
    block = pow(int.from_bytes(header[256:512], 'big'), n.d,
                n.public_numbers.n).to_bytes(256, 'big')
    stop = block.find(b'\0', 2)
    if block[:2] != b'\0\2' or stop < 10 or len(block[stop + 1:]) != 32:
        raise ValueError('RSA key/padding mismatch')
    return block[stop + 1:]


def decrypt_frames(data, header, key):
    segment = struct.unpack_from('<I', header, 144)[0]
    if segment != 80 or len(data) % segment:
        raise ValueError('Expected complete 80-byte frames')
    nonce = bytes(4) + header[132:144]
    for pos in range(0, len(data), segment):
        yield Cipher(algorithms.ChaCha20(key, nonce), None).decryptor().update(data[pos:pos + segment])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--header', required=True)
    p.add_argument('--key', required=True, help='Owner account PEM, local only')
    p.add_argument('--input', required=True)
    p.add_argument('--remote-offset', required=True, type=int)
    p.add_argument('--output', required=True)
    p.add_argument('--follow', action='store_true', help='Decode new complete frames as they arrive')
    p.add_argument('--seconds', type=float, default=0, help='Follow duration; 0 continues until interrupted')
    a = p.parse_args()
    if a.remote_offset < 512 or (a.remote_offset - 512) % 80:
        p.error('Range must start on a payload-frame boundary')
    header = Path(a.header).read_bytes()[:512]
    key = content_key(header, Path(a.key).read_bytes())
    lib = ctypes.CDLL('libopus.so.0')
    lib.opus_decoder_create.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_int)]
    lib.opus_decoder_create.restype = ctypes.c_void_p
    lib.opus_decode.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int,
                               ctypes.POINTER(ctypes.c_int16), ctypes.c_int, ctypes.c_int]
    lib.opus_decoder_destroy.argtypes = [ctypes.c_void_p]
    error = ctypes.c_int()
    decoder = lib.opus_decoder_create(16000, 1, ctypes.byref(error))
    if error.value or not decoder:
        raise RuntimeError('Opus decoder creation failed')
    count = 0
    fd = os.open(a.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    started = time.monotonic()
    try:
        with os.fdopen(fd, 'wb') as f, wave.open(f, 'wb') as w, open(a.input, 'rb') as source:
            w.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
            while True:
                if a.follow and a.seconds > 0 and time.monotonic() - started >= a.seconds:
                    break
                position = source.tell()
                encrypted = source.read(80)
                if len(encrypted) != 80:
                    if not a.follow:
                        if encrypted:
                            raise ValueError('Incomplete trailing frame')
                        break
                    source.seek(position)
                    time.sleep(.1)
                    continue
                frame = next(decrypt_frames(encrypted, header, key))
                buf = (ctypes.c_int16 * 960)()
                n = lib.opus_decode(decoder, frame, 80, buf, 960, 0)
                if n != 320:
                    raise ValueError(f'Unexpected decode result at frame {count}: {n}')
                w.writeframes(bytes(buf)[:n * 2])
                count += 1
                if count % 50 == 0:
                    f.flush()
    except KeyboardInterrupt:
        pass
    finally:
        lib.opus_decoder_destroy(decoder)
    print(json.dumps({'frames': count, 'seconds': count * .02, 'decode_errors': 0}))


if __name__ == '__main__':
    main()
