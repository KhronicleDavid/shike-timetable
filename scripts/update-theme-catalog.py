"""从已打包的ZIP更新一个在线目录条目；上传并公开附件后再推送目录。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--id', required=True)
p.add_argument('--release-tag', required=True)
p.add_argument('--notes-file', type=Path, required=True)
p.add_argument('--minimum-app-code', type=int, default=13)
p.add_argument('--directory', type=Path, default=root/'dist/themes')
a=p.parse_args()
assert re.fullmatch(r'[a-z][a-z0-9_-]{0,39}', a.id) and a.id != 'minimal'
assert re.fullmatch(r'[A-Za-z0-9._-]+', a.release_tag)
config=json.loads((root/'theme-packs'/a.id/'theme.json').read_text(encoding='utf-8-sig'))
notes=a.notes_file.read_text(encoding='utf-8-sig').strip()
assert 0 < len(notes) <= 4000 and a.minimum_app_code > 0
archive=a.directory/f'shike-theme-{a.id}-v{config["version"]}.zip'
with zipfile.ZipFile(archive) as z:
    assert json.loads(z.read('theme.json')) == config, 'ZIP配置与源素材不一致，请重新打包'
catalog_path=root/'distribution/themes.json'
catalog=json.loads(catalog_path.read_text(encoding='utf-8-sig'))
entry=dict(id=a.id,version=config['version'],name=config['name'],description=config['description'],notes=notes,
           minVersionCode=a.minimum_app_code,url=f'https://github.com/KhronicleDavid/shike-timetable/releases/download/{a.release_tag}/{archive.name}',
           bytes=archive.stat().st_size,sha256=hashlib.sha256(archive.read_bytes()).hexdigest())
assert entry['bytes'] <= 128*1024*1024
old=next((x for x in catalog['themes'] if x['id']==a.id), None)
if old:
    assert entry['version'] >= old['version'], '主题版本不可倒退'
    assert entry['version'] != old['version'] or entry['sha256'] == old['sha256'], '同版本素材发生变化，请增加theme.json中的version'
    catalog['themes'][catalog['themes'].index(old)]=entry
else:
    catalog['themes'].append(entry)
catalog_path.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'已更新{entry["name"]}版本{entry["version"]}目录；请核对公开附件后推送。')
