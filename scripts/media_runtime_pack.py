#!/usr/bin/env python3
"""Pack only changed runtime files; never replace the user's package database/config."""
import hashlib,json,pathlib,sys,tarfile

def collect(root):
 result={}
 for p in sorted(pathlib.Path(root).rglob('*')):
  rel=p.relative_to(root).as_posix()
  if rel.split('/')[0] not in ('usr','lib') or rel.startswith('lib/apk/') or p.is_dir():continue
  if p.is_symlink():result[rel]='link:'+str(p.readlink())
  elif p.is_file():
   h=hashlib.sha256()
   with p.open('rb') as f:
    for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
   result[rel]=h.hexdigest()
 return result

def write_delta(before,root,output):
 after=collect(root)
 with tarfile.open(output,'w:gz',compresslevel=6) as t:
  for rel,digest in after.items():
   if before.get(rel)!=digest:
    t.add(pathlib.Path(root)/rel,arcname=rel,recursive=False)

if __name__=='__main__':
 mode,root,path=sys.argv[1:]
 if mode=='snapshot':pathlib.Path(path).write_text(json.dumps(collect(pathlib.Path(root))))
 elif mode=='pack':write_delta(json.loads(pathlib.Path(path+'.json').read_text()),pathlib.Path(root),path)
