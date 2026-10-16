"""生成Release在线更新描述；APK SHA256由GitHub附件digest提供，客户端二次校验。"""
import argparse
import hashlib
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--output', type=Path, default=root/'dist')
a = p.parse_args()
source = (root/'app/build.gradle.kts').read_text(encoding='utf-8')
code = int(re.search(r'versionCode\s*=\s*(\d+)', source)[1])
name = re.search(r'versionName\s*=\s*"([^"]+)"', source)[1]
assert a.apk.is_file()
a.output.mkdir(parents=True, exist_ok=True)
minimum = int(re.search(r'minSdk\s*=\s*(\d+)', source)[1])
manifest = dict(schemaVersion=1, tag='v'+name, versionCode=code, versionName=name, minSdk=minimum,
                assetName=f'shike-{name}-compact.apk')
(a.output/'shike-update.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
(a.output/f'shike-{name}-SHA256.txt').write_text(hashlib.sha256(a.apk.read_bytes()).hexdigest()+'  '+manifest['assetName']+'\n',encoding='utf-8')
print(json.dumps(manifest,ensure_ascii=False))
