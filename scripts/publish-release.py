"""创建可重复验证的应用Release草稿；--publish显式公开并设为最新应用版本。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
API='https://api.github.com/repos/KhronicleDavid/shike-timetable'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--notes-file',type=Path,required=True)
    parser.add_argument('--upload-proxy')
    parser.add_argument('--publish',action='store_true')
    args=parser.parse_args()
    apk=args.apk.resolve(); assert apk.is_file()
    notes=args.notes_file.read_text(encoding='utf-8-sig').strip(); assert notes
    output=ROOT/'dist'
    subprocess.run(['python',str(ROOT/'scripts/prepare-update.py'),'--apk',str(apk),'--output',str(output)],cwd=ROOT,check=True)
    manifest=json.loads((output/'shike-update.json').read_text(encoding='utf-8'))
    tag=manifest['tag']; version=manifest['versionName']
    env=dict(os.environ,GCM_INTERACTIVE='Never',GIT_TERMINAL_PROMPT='0')
    credential=subprocess.run(['git','credential','fill'],cwd=ROOT,input='protocol=https\nhost=github.com\n\n',text=True,capture_output=True,env=env)
    assert credential.returncode==0,'GitHub credential unavailable'
    token=dict(row.split('=',1) for row in credential.stdout.splitlines() if '=' in row).get('password')
    assert token,'GitHub credential unavailable'
    headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','User-Agent':'Shike-release'}

    def api(path,method='GET',data=None,file=None,mime=None):
        url=path if path.startswith('https://') else API+path
        h=dict(headers); body=None
        if data is not None:
            body=json.dumps(data,ensure_ascii=False).encode();h['Content-Type']='application/json'
        if file is not None:
            content=file.read_bytes();h['Content-Type']=mime;h['Content-Length']=str(len(content))
            body=(memoryview(content)[i:i+256*1024] for i in range(0,len(content),256*1024))
        proxies={'https':args.upload_proxy} if file is not None and args.upload_proxy else {}
        opener=urllib.request.build_opener(urllib.request.ProxyHandler(proxies))
        for attempt in range(3 if method=='GET' else 1):
            try:
                with opener.open(urllib.request.Request(url,headers=h,method=method,data=body),timeout=900 if file else 60) as response:
                    return json.load(response)
            except urllib.error.HTTPError: raise
            except (urllib.error.URLError,TimeoutError):
                if method!='GET' or attempt==2: raise
                time.sleep(2)

    def upload(release,name,path,mime):
        existing=next((a for a in api(f'/releases/{release["id"]}')['assets'] if a['name']==name),None)
        if existing is None:
            address=release['upload_url'].split('{')[0]+'?'+urllib.parse.urlencode(dict(name=name))
            existing=api(address,'POST',file=path,mime=mime)
        assert existing['size']==path.stat().st_size and existing['digest']=='sha256:'+hashlib.sha256(path.read_bytes()).hexdigest(),f'Asset mismatch: {name}'
        print('VERIFIED '+name,flush=True)

    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip()
    if args.publish:
        assert not subprocess.check_output(['git','status','--porcelain'],cwd=ROOT).strip(),'Commit workspace changes before publishing'
        assert api('/commits/'+head)['sha']==head,'Push HEAD before publishing'
    release=next((r for r in api('/releases?per_page=100') if r['tag_name']==tag),None)
    if release is None:
        release=api('/releases','POST',dict(tag_name=tag,target_commitish=head,name='拾课 '+version,draft=True,prerelease=False,body=notes))
    upload(release,manifest['assetName'],apk,'application/vnd.android.package-archive')
    upload(release,'shike-update.json',output/'shike-update.json','application/json')
    upload(release,f'shike-{version}-SHA256.txt',output/f'shike-{version}-SHA256.txt','text/plain; charset=utf-8')
    if not args.publish:
        print('Release draft ready: '+tag);return
    if release['draft']:
        release=api(f'/releases/{release["id"]}','PATCH',dict(target_commitish=head,body=notes,draft=False,prerelease=False,make_latest='true'))
    ref=api('/git/ref/tags/'+tag)['object']
    if ref['type']=='tag':ref=api('/git/tags/'+ref['sha'])['object']
    assert ref['sha']==head,'Release tag does not match HEAD'
    public=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with public.open(urllib.request.Request(API+'/releases/latest',headers={'User-Agent':'Shike-public-check'}),timeout=60) as response:
        latest=json.load(response)
    assert latest['tag_name']==tag and not latest['draft']
    print('PUBLISHED '+latest['html_url'],flush=True)


if __name__=='__main__':
    main()
