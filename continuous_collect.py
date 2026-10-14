"""Direct BLE collector with segmented WAV output and persistent resume cursor.

The official app must release its BLE connection first. No phone/ADB dependency.
Bootstrap credentials remain in local private-* files; never logged.
"""
import argparse
import json
import os
import signal
import subprocess
import sys
import time
import wave
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resume', required=True, help='Existing verified byte-capture metadata')
    parser.add_argument('--directory', required=True, help='Private output directory')
    parser.add_argument('--seconds', type=float, default=0, help='0: keep collecting until interrupted')
    parser.add_argument('--segment-seconds', type=float, default=60)
    args = parser.parse_args()
    if args.seconds < 0 or not 1 <= args.segment_seconds <= 3600:
        parser.error('Invalid duration')
    state = json.loads(Path(args.resume).read_text())
    cursor, session_id = state['remote_end'], state['session_id']
    root = Path(__file__).parent
    dest = Path(args.directory)
    dest.mkdir(parents=True, exist_ok=True, mode=0o700)
    if dest.stat().st_mode & 0o077:
        parser.error('Output directory must be owner-only (0700)')
    started = time.monotonic()
    collector = decoder = None
    failures = 0
    try:
        while args.seconds == 0 or time.monotonic() - started < args.seconds:
            seconds = args.segment_seconds
            if args.seconds:
                seconds = min(seconds, max(1, args.seconds - (time.monotonic() - started)))
            name = dest / f'{session_id}-{cursor}-{time.time_ns()}'
            raw = str(name) + '.bin'
            with open(str(name) + '.log', 'x') as log:
                os.chmod(log.name, 0o600)
                collector = subprocess.Popen([sys.executable, str(root / 'direct_probe.py'),
                    '--start', str(cursor), '--session', str(session_id), '--seconds', str(seconds),
                    '--output', raw], stdout=log, stderr=subprocess.STDOUT)
                while collector.poll() is None and not Path(raw + '.header.bin').exists():
                    time.sleep(.1)
                if Path(raw + '.header.bin').exists():
                    while collector.poll() is None and not Path(raw).exists():
                        time.sleep(.1)
                    if Path(raw).exists():
                        decoder = subprocess.Popen([sys.executable, str(root / 'decode_cache.py'),
                            '--header', raw + '.header.bin', '--key', str(Path(os.environ.get('NOTEPIN_AUTH_DIR', str(root / 'private-auth'))) / 'private-account-key.pem'),
                            '--input', raw, '--remote-offset', str(cursor), '--output', str(name) + '.wav',
                            '--follow'], stdout=log, stderr=subprocess.STDOUT)
                code = collector.wait()
                if decoder is not None:
                    # Drain every complete acquired frame before finalizing this segment.
                    expected_samples = Path(raw).stat().st_size // 80 * 320
                    deadline = time.monotonic() + 10
                    wav = Path(str(name) + '.wav')
                    while time.monotonic() < deadline and wav.stat().st_size < 44 + expected_samples * 2:
                        if decoder.poll() is not None:
                            break
                        time.sleep(.1)
                    decoder.send_signal(signal.SIGINT)
                    decode_code = decoder.wait(timeout=10)
                    decoder = None
                    if decode_code:
                        raise RuntimeError('Audio decode failed; see private segment log')
                    with wave.open(str(wav), 'rb') as audio:
                        if audio.getnframes() != expected_samples:
                            raise RuntimeError('Audio segment not completely drained')
                metadata = Path(raw + '.json')
                if metadata.exists():
                    saved = json.loads(metadata.read_text())
                    if saved['session_id'] != session_id or saved['remote_start'] != cursor:
                        raise RuntimeError('Resume metadata mismatch')
                    cursor = saved['remote_end']
                    state = {'session_id': session_id, 'remote_end': cursor,
                             'source': 'DIRECT_BLE_NO_OFFICIAL_APP', 'last_capture': raw}
                    temp = dest / 'resume.json.tmp'
                    temp.write_text(json.dumps(state, indent=2) + '\n')
                    temp.replace(dest / 'resume.json')
                    print(json.dumps(state), flush=True)
                if code:
                    failures += 1
                    if failures >= 3:
                        raise RuntimeError('Three connection failures; inspect private logs')
                    time.sleep(3)
                else:
                    failures = 0
    except KeyboardInterrupt:
        pass
    finally:
        for process in [collector, decoder]:
            if process is not None and process.poll() is None:
                process.send_signal(signal.SIGINT)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=10)


if __name__ == '__main__':
    main()
