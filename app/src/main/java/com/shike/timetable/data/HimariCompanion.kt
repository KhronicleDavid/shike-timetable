package com.shike.timetable.data

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

enum class HimariDayPart(val label: String) { Morning("早晨"), Noon("午间"), Evening("夜晚") }

data class HimariMoment(val part: HimariDayPart, val artwork: String, val line: String)

/** 按用户提供的角色 skill 创作的应用台词，均非原作引文。 */
object HimariCompanion {
    val morningLines = listOf(
        "早、早上好……今天的课表，我陪你一起看看吧。",
        "书带好了吗？嗯，教室也再确认一下哦。",
        "啊哈哈，还没睡醒？那我们先看第一节课吧。",
        "别光顾着赶路，早餐也要记得吃呀。",
        "那、那个……提前一点出门，就不用慌慌张张啦。",
        "今天也一点一点来。我会给你加油的。",
        "诶嘿嘿，准备齐全的早晨，看着就很安心呢。",
        "嗯——把要用的笔记本放上面，会更方便吧？",
        "你猜猜，忘带书和忘看教室，哪个更麻烦？",
        "早上的时间很珍贵，可别全交给赖床哦。",
        "那、那个……你头发有点翘。先别急着跑呀。",
        "嗯——先拉开窗帘？让早晨有个早晨的样子嘛。",
        "你说再睡五分钟？这句话已经很有续集的气势了。",
        "今天的第一件小事，就从把水杯带好开始吧。",
        "啊哈哈，精神还在被窝里？我先替你看着课表。",
        "早餐选不出来的话，先选能好好坐下吃的吧。",
        "闹钟已经很努力了，你也稍微配合一下嘛。",
        "诶嘿嘿，提前到教室的话，还能慢慢翻两页书。",
        "今天要用哪支笔？诶，只要不是那支没墨的就好。",
        "路上的小花也可以看一眼，但要先看好脚下哦。",
        "说起来，清晨的翻页声，听着很让人安静呢。",
        "我可没说要比赛。不过，准时出门这局不能输。",
        "那、那个……衣领翻起来了。整理一下会更精神。",
        "出门前摸摸口袋吧，钥匙可不会自己跟上来。",
        "如果今天有点紧张，我们就先做好眼前这一件。",
        "啊哈哈，我也会手忙脚乱呀。所以才要早点准备嘛。",
        "早晨的计划不用很壮观，能真的做到就很好了。",
        "嗯——拿杯热饮的时候，小心别碰到杯口哦。",
        "别边走边补笔记啦。字会歪，人也会走歪的。",
        "你猜猜，今天会先遇到好天气，还是有趣的课？",
        "诶？想听鼓励？那、那就……今天也加油呀！",
        "如果路边出现小狗……只看一会儿，一会儿就好！",
        "我觉得，把书包收拾好也算一个漂亮的开场呢。",
        "昨天没读完的那一页，留到课间也来得及。",
        "嗯——睡意还没散？先站稳，再伸个懒腰吧。",
        "有些电影的清晨很漂亮，不过你的第一节课要赶上哦。",
        "诶嘿嘿，今天也见到你了。准备出发了吗？",
        "先深呼吸。好啦，今天不是非得一口气跑完的。",
    )
    val noonLines = listOf(
        "上午辛苦啦。先好好吃饭，再想下午的事吧。",
        "嗯——午休不用安排得满满的，留一点空白吧。",
        "下午在哪间教室？我陪你再看一眼好了。",
        "那、那个……看了这么久，稍微歇一会儿吧。",
        "啊哈哈，别一边赶路一边找课表，会手忙脚乱的。",
        "如果还有时间，翻几页喜欢的书也不错呢。",
        "下午也别着急，一门一门来就好。",
        "诶嘿嘿，午后的计划整理好了，心情也轻松了。",
        "再忙也要留出午饭时间。这一点我可不会让步哦。",
        "课间有空的话，陪我聊聊最近读的书吧？",
        "午饭之后走慢一点吧，下午又不会偷偷提前开始。",
        "诶？把午休全塞进待办？你是不是漏掉了“休”字？",
        "那、那个……水杯空了。顺路接一点水吧。",
        "刚才那节课很难吗？先记下问题，不用急着全弄懂。",
        "有空位就坐一会儿吧，书包也想休息一下呢。",
        "嗯——这段情节有点可疑。午休后我再仔细想想。",
        "你猜猜，看书和打瞌睡，哪个会先赢？",
        "啊哈哈，光看菜名就挑花眼了？先别挡住后面的人哦。",
        "下午换教室的话，出发前再确认一次楼号吧。",
        "等事情做完再休息？那休息岂不是永远排不上呀。",
        "看到仓鼠鼓着脸颊，我就……呜，真的好可爱！",
        "嗯——可爱的小动物照片，只看三张。大概三张。",
        "别拿“我还不饿”当忙碌勋章啦，先留点吃饭时间。",
        "借来的书要放好哦，折起一角就不太礼貌了。",
        "这本书如果拍成电影，你觉得开场该选哪一幕？",
        "啊哈哈，这个笑话……嗯，气氛倒是安静下来了。",
        "那、那个……你刚刚笑了。好像稍微有精神了呢。",
        "下午的事，一件件排好就行。空白也要有座位。",
        "要是突然多了一件事，就先看看哪件能晚一点做吧。",
        "诶嘿嘿，一起把杂乱的小事理顺，还挺有成就感呢。",
        "嗯——课间太短的话，就别绕远路买东西啦。",
        "雨伞放哪了？出门前看看窗外吧。",
        "书签找不到了？用一张干净的纸也可以呀。",
        "如果这一页读不进去，就先看看远处的树吧。",
        "我没在催你哦。只是再不出发，就真的要跑了。",
        "诶？夸我考虑得周到？我、我只是多看了一眼而已。",
        "午后的阳光如果刚刚好，发一会儿呆也可以吧。",
        "吃完后别把餐具落下。收拾好了，才算圆满结束呢。",
    )
    val eveningLines = listOf(
        "今天辛苦啦。还有没整理完的笔记吗？",
        "嗯——复习完这一点，就给自己留点休息时间吧。",
        "明天的书先放进包里，早上会从容一些哦。",
        "那、那个……已经很晚的话，就别勉强自己啦。",
        "我喜欢安静翻书的时间，你呢？",
        "啊哈哈，今天的计划完成了？那就好好奖励一下自己吧。",
        "别把休息也当成一项必须赶完的作业哦。",
        "诶嘿嘿，和你一起把明天准备好，也挺开心的。",
        "一页一页看就好。不用今晚把所有事都做完。",
        "晚安……明天也记得来看看课表呀。",
        "那、那个……灯有点暗。看书前先调整一下吧。",
        "今天做得不够好吗？你已经认真走了很长一段啦。",
        "嗯——明天最重要的事，写一条就先够了。",
        "你说最后一集？这句话和早上的五分钟很像呢。",
        "啊哈哈，电影还没结束，眼皮倒先谢幕了。",
        "书签夹好了。剩下的故事，明天再见也很好呀。",
        "晚上的风有点凉的话，就把外套带上吧。",
        "我很喜欢好故事结束后，那一点舍不得的安静。",
        "你猜猜，刚才那个伏笔是不是一开始就藏在那里？",
        "诶嘿嘿，要是能聊到同一本书，总觉得特别开心。",
        "把桌上的杯子收好吧，别让它和笔记挤在一起。",
        "嗯——不想做的事，先拆成小小的一步试试看？",
        "明天的闹钟设好了吗？这次要给它一点面子哦。",
        "今天有不顺利的事吗？那、那个……我听着呢。",
        "啊哈哈，我不太会说漂亮话。不过，你辛苦了。",
        "还想再撑一会儿？可休息又不是认输呀。",
        "不用把每一天都写成高潮，平静的章节也很好。",
        "这部电影的结尾，我可要认真和你讨论一下。",
        "诶？想让我剧透？不行，那个惊喜要留给你自己。",
        "晚点再收书包的话，早晨的你可是会抗议的哦。",
        "嗯——今天哪件小事让你高兴？我想听听看。",
        "仓鼠抱着食物的样子……啊，想到就忍不住笑了。",
        "不是所有问题都得今晚想明白，先记在纸上吧。",
        "那、那个……你已经揉了好几次眼睛啦。",
        "一起看星星的话，先站在安全又亮堂的地方吧。",
        "诶嘿嘿，今天也帮上了一点忙的话，就太好了。",
        "把窗边的书挪远一点吧，别让夜风把书页吹乱。",
        "晚安。今天没做完的那一点，就留给明天的我们吧。",
    )
    val quietLines = listOf(
        "今天没课的话，做点自己喜欢的事吧。",
        "嗯——留白也很好呀，不用每一格都填满。",
        "那、那个……教室还没确定，就先记下来别忘了。",
        "课表整理好啦。接下来会轻松一点吧？",
        "诶？这个关键词没找到呢，换一个试试看？",
        "啊哈哈，我只是想帮上一点忙而已。",
    )
    val allLines: List<String> = morningLines + noonLines + eveningLines + quietLines
    val artworks = mapOf(
        HimariDayPart.Morning to listOf("himari_morning_wave", "himari_morning_bag", "himari_morning_coffee", "himari_morning_stretch"),
        HimariDayPart.Noon to listOf("himari_noon_lunch", "himari_noon_break", "himari_noon_hamster", "himari_noon_library"),
        HimariDayPart.Evening to listOf("himari_evening_read", "himari_evening_rest", "himari_evening_stars", "himari_evening_goodnight"),
    )
    fun partAt(time: LocalTime): HimariDayPart = when (time.hour) {
        in 5..10 -> HimariDayPart.Morning
        in 11..16 -> HimariDayPart.Noon
        else -> HimariDayPart.Evening
    }

    /** 同一半小时内保持一致，应用与小组件可用它判断自动轮换的边界。 */
    fun bucketAt(moment: LocalDateTime): Long =
        moment.toLocalDate().toEpochDay() * 48 + moment.hour * 2 + moment.minute / 30

    fun at(moment: LocalDateTime): HimariMoment {
        val part = partAt(moment.toLocalTime())
        val bucket = bucketAt(moment)
        val lines = linesFor(part)
        val pictures = artworks.getValue(part)
        return HimariMoment(part, pictures[Math.floorMod(bucket, pictures.size.toLong()).toInt()],
            lines[Math.floorMod(bucket, lines.size.toLong()).toInt()])
    }

    /** 点击时只从当前时段选取；优先避开最近五句，且不连续重复同一句或同一幅图。 */
    fun randomNext(
        moment: LocalDateTime,
        current: HimariMoment,
        recentLines: List<String>,
        random: Random = Random.Default,
    ): HimariMoment {
        val part = partAt(moment.toLocalTime())
        val alternatives = linesFor(part).filterNot { it == current.line }
        val recent = recentLines.takeLast(5)
        var candidates = alternatives.filterNot { it in recent }
        // 若未来缩小台词池，先放回最早的一句；当前句始终不放回。
        for (oldestToRelease in 1..recent.size) {
            if (candidates.isNotEmpty()) break
            candidates = alternatives.filterNot { it in recent.drop(oldestToRelease) }
        }
        val pictures = artworks.getValue(part).filterNot { it == current.artwork }
        return HimariMoment(part, pictures.random(random), candidates.random(random))
    }

    private fun linesFor(part: HimariDayPart): List<String> = when (part) {
        HimariDayPart.Morning -> morningLines
        HimariDayPart.Noon -> noonLines
        HimariDayPart.Evening -> eveningLines
    }

    fun quietLine(moment: LocalDateTime): String = quietLines[Math.floorMod(moment.toLocalDate().toEpochDay(), 2L).toInt()]

    /** 在午夜或时段交界刷新；定期更新另由系统负责，不申请精确闹钟权限。 */
    fun nextBoundary(moment: LocalDateTime): LocalDateTime = listOf(5, 11, 17)
        .map { moment.toLocalDate().atTime(it, 0) }.firstOrNull { it.isAfter(moment) }
        ?: moment.toLocalDate().plusDays(1).atStartOfDay()
}
