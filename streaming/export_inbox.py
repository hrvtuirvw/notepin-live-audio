"""Example downstream interface: export committed chunks without marking them processed."""
import argparse,json,os,sqlite3,wave
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--inbox',required=True);p.add_argument('--output',required=True)
a=p.parse_args();os.umask(0o077)
out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
db=sqlite3.connect(f'file:{Path(a.inbox).resolve()}?mode=ro',uri=True)
for row in db.execute('SELECT rowid,metadata,pcm FROM chunks WHERE consumed=0 ORDER BY CASE mode WHEN "live" THEN 0 ELSE 1 END,recording_id,start_sample'):
    i,metadata,pcm=row
    with wave.open(str(out/f'{i}.wav'),'wb') as w:
        w.setparams((1,2,16000,0,'NONE','not compressed'));w.writeframes(pcm)
    (out/f'{i}.json').write_text(metadata+'\n')
print('Exported durable audio chunks; transcription/advice worker can consume the same SQLite inbox.')
