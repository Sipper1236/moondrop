#!/usr/bin/env python3
"""Sign all release artifacts. Key remains outside source and CI. Requires OpenSSL."""
import argparse, hashlib, pathlib, re, subprocess
p=argparse.ArgumentParser();p.add_argument('--version',required=True);p.add_argument('--key',required=True,type=pathlib.Path);p.add_argument('--directory',required=True,type=pathlib.Path);a=p.parse_args()
if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?',a.version): p.error('invalid version')
files=sorted(f for f in a.directory.iterdir() if f.is_file() and f.name not in {'release-manifest.txt','release-manifest.sig','SHA256SUMS'})
if not files: p.error('no artifacts')
entries=[]
for f in files:
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*',f.name): p.error('unsafe artifact name')
    entries.append(hashlib.file_digest(f.open('rb'),'sha256').hexdigest()+'  '+f.name+'\n')
manifest=a.directory/'release-manifest.txt';manifest.write_text('moondrop-release-v1\nversion '+a.version+'\n'+''.join(entries),encoding='utf-8',newline='\n')
subprocess.run(['openssl','pkeyutl','-sign','-rawin','-inkey',str(a.key),'-in',str(manifest),'-out',str(a.directory/'release-manifest.sig')],check=True)
(a.directory/'SHA256SUMS').write_text(''.join(entries),encoding='ascii')
print('Signed',len(files),'release artifacts; private key was not copied.')
