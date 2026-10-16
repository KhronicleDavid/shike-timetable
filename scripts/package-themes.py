"""生成独立主题ZIP；不修改图片、语录或声音，安装包只带简约主题。"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=root/'dist/themes')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = []
    for directory in sorted((root/'theme-packs').iterdir()):
        if not directory.is_dir():
            continue
        theme = json.loads((directory/'theme.json').read_text(encoding='utf-8-sig'))
        assert theme['id'] == directory.name
        target = args.output/f"shike-theme-{theme['id']}-v{theme['version']}.zip"
        with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for path in sorted(directory.rglob('*')):
                if not path.is_file():
                    continue
                name = path.relative_to(directory).as_posix()
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, path.read_bytes())
        rows.append(dict(id=theme['id'], name=theme['name'], version=theme['version'],
                         file=target.name, bytes=target.stat().st_size,
                         sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
    (args.output/'themes.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
    print(json.dumps(rows, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
