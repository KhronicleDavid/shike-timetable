"""从固定版本的一言开放句库和人工核对的网页短摘生成离线每日一言。"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request
import urllib.parse

ROOT=Path(__file__).resolve().parents[1]
COMMIT='8b30ce7566877bf2a1c5e07ee5199afcedd5b3ad'
BASE=f'https://raw.githubusercontent.com/hitokoto-osc/sentences-bundle/{COMMIT}/'
C_IDS=[287,294,298,300,661,662,663,934,3662,4154,4155,4261,4448,4449,4472,4473,4474,4500,5415,6903,7017,7018,7019,7099,7535,7765,7819,8071,8135,8136,8140,8337,8340,8523,8532,8605,8878,8879,9314,9584,9586,9687,9808,9851,9873,9874,9887,9888,9931,10431,10437,10495,10854,10855,10856,10857,10858,10859,10861,10863,10866,11334,11335,11337,11348,11385]
A_IDS=[37,84,107,118,187,194,197,209,212,226,238,242,260,261,867,888,3670,6250,6754,6755,8886,8907]
# Key原作的常见共通名句在上游归入动画类；保留原分类记录，不把它们伪称为官方汉化文本。
NAMES={'Eden':'eden*','SummerPocket':'Summer Pockets','Clover Day\'s':'幸运草的约定','樱花，萌放。':'樱花，萌放。','樱花、萌放':'樱花，萌放。',
       '亚托莉～我挚爱的时光':'ATRI -My Dear Moments-','亚托莉——我挚爱的时光':'ATRI -My Dear Moments-','ヨスガノソラ':'缘之空',
       '爱上火车Lastrun':'爱上火车 Last Run!!','CLANNAD':'CLANNAD','Clannad':'CLANNAD','clannad':'CLANNAD','Fate/Stay night':'Fate/stay night','narcissu':'水仙 narcissu'}
PLAYED={'樱之诗','樱之刻','金色Loveriche','美好的每一天','魔法使之夜','秽翼的尤斯蒂娅','樱花，萌放。','幸运草的约定','永不枯萎的世界与终焉之花'}
WP='https://galgamewords.wordpress.com/'
# (中文短摘, 作品, 摘录页面, 说明)。仅截取完整短句或独立分句，不补写游戏台词。
MANUAL=[
 ('只要行动，可能性就会诞生。','金色Loveriche','https://note.com/lovelykura_no_su/n/n4fa273275745','日文台词自译：行動を起こせば可能性が生まれる。'),
 ('终于，我们能因为心意而在一起，而不再是职责。','金色Loveriche','https://genben.hatenablog.jp/entry/2025/03/15/103827','日文台词自译：ようやく役職でなく、気持ちで一緒にいられるわね。'),
 ('所有的时光，都是黄金时光。','金色Loveriche','https://note.com/quirky_myrtle112/n/n66ddc26074a4','日文短摘自译：すべての時間がゴールデンタイムだから。'),
 ('我要为你准备一个最棒的今天。','金色Loveriche','https://note.com/quirky_myrtle112/n/n66ddc26074a4','日文短摘自译：最高の今日を用意する。'),
 ('只有全力挑战过的人，才会真正败北。','樱之刻','https://baskmedia.jp/sakuranotoki-netabare/','日文短摘自译：本当に敗北出来るのは！本気で挑んだニンゲンだけザンス！'),
 ('你未完成的画笔，是追求无限的翅膀。','樱之刻','https://baskmedia.jp/sakuranotoki-netabare/','日文短摘自译：お前の、未完な筆は、無限を目指す翼だ。'),
 ('长夜伴浪破晓梦，梦晓破浪伴夜长。','樱之诗','https://moegirl.icu/zh-my/User:%E6%A0%80%E6%A2%A6/Galgame%E6%91%98%E5%BD%95','原作引用的和歌回文中文译句，参考页面所署作品。'),
 ('人啊，幸福地活下去吧。','美好的每一天','https://www.bilibili.com/video/BV1uxbezvEoQ/','公开视频标题短摘。'),
 ('向日葵坡道的后面，是美好的每一天。','美好的每一天','https://www.bilibili.com/video/BV1uxbezvEoQ/','公开视频标题短摘。'),
 ('黄昏时吹来的风，有些冷，却也让人安心。','魔法使之夜','https://www.dujingdian.cn/au/sayinglist/d8wt5opBdu-2','从页面明确标注魔法使之夜的段落截取独立短句。'),
 ('回忆总会逐渐朦胧。','魔法使之夜','https://www.dujingdian.cn/au/sayinglist/d8wt5opBdu-2','同段独立短句。'),
 ('在黑与白之间的独立舞台间漫步，念诵黑色童话的言咒。','魔法使之夜','https://www.dujingdian.cn/au/sayinglist/d8wt5opBdu','从页面明确标注魔法使之夜的段落截取独立分句。'),
 ('不知者无罪。但是，知而无为，就是一种不可否认的罪孽。','秽翼的尤斯蒂娅',WP+'2022/09/20/《秽翼的尤斯蒂娅》/','合并网页换行。'),
 ('只要鸟儿和风，永远永远，都是那样的自由。','秋之回忆2',WP+'2022/10/08/《秋之回忆2》/','独立短句。'),
 ('这个世界，往前便是未来，往后便是回忆，取一角便是故事。','灰色的果实',WP+'2022/10/02/《灰色的果实》/','合并网页换行。'),
 ('我们相遇在一眨眼的“瞬间”。','LUNARiA',WP+'2022/09/26/《lunaria》/','独立短句。'),
 ('没有名字的东西，就算能感知到，也仍是无法理解的。','青空下的加缪',WP+'2022/09/23/《青空下的加缪》/','合并网页换行。'),
 ('虽然绕了好大好远的弯子，但还是要……——再做一次尝试。','Re:Lief～献给亲爱的你～',WP+'2022/09/21/《relief献给亲爱的你》/','合并网页换行。'),
 ('正因为能感到悲伤，我们才被允许拥有感情。','Harmonia',WP+'2022/09/21/《harmonia》/','独立短摘。'),
 ('我、我呀，想活着。想一直活下去。','加奈，欢迎回来！',WP+'2022/09/21/《加奈，欢迎回来！》/','独立短句，去除外层引号。'),
 ('哪里都可以去，也就是哪里都没有我的容身之处。','悠久之翼',WP+'2022/09/19/《悠久之翼》/','独立分句。'),
 ('人最能感到幸福的，是将要跨越障碍的那个瞬间。','星恋＊Twinkle',WP+'2022/09/20/《星恋＊twinkle》/','合并网页换行。'),
 ('人正因流泪才可以忍耐住每天发生的事。','不要践踏天使的羽毛',WP+'2022/09/20/《不要践踏天使的羽毛》/','从已核对摘录中截取独立分句。'),
]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache',type=Path,default=ROOT/'work/daily-quotes-upstream')
    a=p.parse_args();a.cache.mkdir(parents=True,exist_ok=True)
    output=ROOT/'app/src/main/assets/quotes';output.mkdir(parents=True,exist_ok=True)
    rows=[]
    for kind,ids in [('c',C_IDS),('a',A_IDS)]:
        path=a.cache/f'{kind}.json'
        if not path.exists():
            with urllib.request.urlopen(BASE+f'sentences/{kind}.json',timeout=45) as r:path.write_bytes(r.read())
        raw=path.read_text(encoding='utf-8')
        lookup={x['id']:x for x in json.loads(raw)}
        source_lines=raw.splitlines()
        for id in ids:
            item=lookup[id];text=item['hitokoto'].strip();work=NAMES.get(item['from'],item['from'])
            line=next(i+1 for i,value in enumerate(source_lines) if item['uuid'] in value)
            rows.append(dict(id='h-'+item['uuid'],text=text,work=work,speaker='',sourceUrl=f'https://github.com/hitokoto-osc/sentences-bundle/blob/{COMMIT}/sentences/{kind}.json#L{line}',
                upstreamId=id,upstreamCategory=kind,preferred=work in PLAYED,license='AGPL-3.0',note='社区收录译句；保留上游文字，不标注未经证实的说话人。'))
    for text,work,url,note in MANUAL:
        url=urllib.parse.quote(url,safe=':/%?=&')
        rows.append(dict(id='web-'+hashlib.sha256((work+'\n'+text).encode()).hexdigest()[:16],text=text,work=work,speaker='',sourceUrl=url,
            preferred=work in PLAYED,license='原作权利人保留权利（短摘）',note=note))
    assert len(rows)>=100
    assert len({x['text'] for x in rows})==len(rows)
    assert all(0<len(x['text'])<=50 and '\n' not in x['text'] for x in rows)
    rows.sort(key=lambda x:not x['preferred'])
    data=dict(schemaVersion=1,version=1,reviewedAt='2026-11-08',upstreamCommit=COMMIT,quotes=rows)
    (output/'daily.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    license_path=a.cache/'LICENSE'
    if not license_path.exists():
        with urllib.request.urlopen(BASE+'LICENSE',timeout=45) as r:license_path.write_bytes(r.read())
    (output/'AGPL-3.0.txt').write_bytes(license_path.read_bytes())
    print(json.dumps(dict(count=len(rows),preferred=sum(x['preferred'] for x in rows),works=len({x['work'] for x in rows}),maxLength=max(len(x['text']) for x in rows)),ensure_ascii=False))

if __name__=='__main__':main()
