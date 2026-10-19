#!/usr/bin/env bash
# Apply reviewed dependency locks to the exact upstream 1.14.3 source checkout.
# Source implementation is unchanged; Go's checksum database remains enabled.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_dir="${1:?Usage: engine-security-patch.sh /path/to/pinned-sing-box}"
python3 - "$source_dir" "$script_dir/../android/native" <<'PY'
import hashlib,json,pathlib,shutil,subprocess,sys
source=pathlib.Path(sys.argv[1]).resolve();patch=pathlib.Path(sys.argv[2]).resolve()
metadata=json.loads((patch/'security-patch.json').read_text())
if subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()!=metadata['upstream_commit']:
 raise SystemExit('Refusing security patch for an unpinned upstream source checkout.')
modified=subprocess.check_output(['git','-C',str(source),'diff','--name-only','HEAD','--','.'],text=True).splitlines()
if set(modified)-{'go.mod','go.sum'}:
 raise SystemExit('Refusing unreviewed modifications to upstream engine source.')
for name,record in metadata['files'].items():
 current=hashlib.sha256((source/name).read_bytes()).hexdigest()
 if current not in {record['original_sha256'],record['patched_sha256']}:
  raise SystemExit('Refusing unexpected upstream dependency lock changes.')
 replacement=patch/record['patch_file']
 if hashlib.sha256(replacement.read_bytes()).hexdigest()!=record['patched_sha256']:
  raise SystemExit('The reviewed engine security dependency lock failed its integrity check.')
for name,record in metadata['files'].items():shutil.copyfile(patch/record['patch_file'],source/name)
print('Applied reviewed Moondrop engine security dependency locks.')
PY
