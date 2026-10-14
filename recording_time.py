"""Infer recording start from the owner's NotePin uint32 Unix-second recording ID."""
from datetime import datetime, timezone
from zoneinfo import ZoneInfo


def recording_times(session_id):
    if not isinstance(session_id, int) or isinstance(session_id, bool) or not 0 < session_id <= 0xffffffff:
        raise ValueError('Recording ID must be a positive uint32')
    instant = datetime.fromtimestamp(session_id, timezone.utc)
    return {'recording_start_utc': instant.isoformat(),
            'recording_start_jst': instant.astimezone(ZoneInfo('Asia/Tokyo')).isoformat(),
            'recording_start_source': 'device_recording_id_unix_seconds'}
