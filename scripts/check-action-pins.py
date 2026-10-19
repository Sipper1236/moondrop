#!/usr/bin/env python3
import pathlib,re,sys
bad=[]
for p in pathlib.Path('.github/workflows').glob('*.y*ml'):
 for n,line in enumerate(p.read_text().splitlines(),1):
  m=re.search(r'\buses:\s*([^\s#]+)',line)
  if m and not m[1].startswith('./') and not re.fullmatch(r'[\w./-]+@[0-9a-f]{40}',m[1]):bad.append(f'{p}:{n}: mutable action: {m[1]}')
if bad:sys.exit('\n'.join(bad))
print('All workflow actions are pinned to immutable full commit SHAs.')
