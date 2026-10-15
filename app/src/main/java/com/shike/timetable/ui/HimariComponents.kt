package com.shike.timetable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import com.shike.timetable.R
import com.shike.timetable.data.HimariCompanion
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin

enum class HimariArtwork(val drawable: Int) {
    Greeting(R.drawable.himari_home), Study(R.drawable.himari_study), Rest(R.drawable.himari_rest), Original(R.drawable.himari),
    MorningWave(R.drawable.himari_morning_wave), MorningBag(R.drawable.himari_morning_bag),
    NoonLunch(R.drawable.himari_noon_lunch), NoonBreak(R.drawable.himari_noon_break),
    EveningRead(R.drawable.himari_evening_read), EveningRest(R.drawable.himari_evening_rest),
    MorningCoffee(R.drawable.himari_morning_coffee), MorningStretch(R.drawable.himari_morning_stretch),
    NoonHamster(R.drawable.himari_noon_hamster), NoonLibrary(R.drawable.himari_noon_library),
    EveningStars(R.drawable.himari_evening_stars), EveningGoodnight(R.drawable.himari_evening_goodnight)
}
val LocalHimariTime = staticCompositionLocalOf { LocalDateTime.now() }
val LocalHimariMoment = staticCompositionLocalOf { HimariCompanion.at(LocalDateTime.now()) }
val LocalHimariShuffle = staticCompositionLocalOf<(() -> Unit)?> { null }
internal fun himariArtworkResource(key: String): Int = when (key) {
    "himari_morning_wave" -> R.drawable.himari_morning_wave
    "himari_morning_bag" -> R.drawable.himari_morning_bag
    "himari_noon_lunch" -> R.drawable.himari_noon_lunch
    "himari_noon_break" -> R.drawable.himari_noon_break
    "himari_evening_read" -> R.drawable.himari_evening_read
    "himari_evening_rest" -> R.drawable.himari_evening_rest
    "himari_morning_coffee" -> R.drawable.himari_morning_coffee
    "himari_morning_stretch" -> R.drawable.himari_morning_stretch
    "himari_noon_hamster" -> R.drawable.himari_noon_hamster
    "himari_noon_library" -> R.drawable.himari_noon_library
    "himari_evening_stars" -> R.drawable.himari_evening_stars
    "himari_evening_goodnight" -> R.drawable.himari_evening_goodnight
    else -> R.drawable.himari_home
}
@Composable private fun timedHimariArtwork(): HimariArtwork {
    val drawable = himariArtworkResource(LocalHimariMoment.current.artwork)
    return HimariArtwork.entries.first { it.drawable == drawable }
}

/** 与角色发饰呼应的小花；装饰没有点击或播报行为。 */
@Composable fun HimariFlower(modifier: Modifier = Modifier) {
    val petals = MaterialTheme.colorScheme.surface
    val center = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        val radius = size.minDimension / 2
        repeat(8) { i ->
            val angle = i * Math.PI / 4
            drawOval(petals,
                topLeft = Offset(size.width / 2 + cos(angle).toFloat() * radius * .52f - radius * .29f,
                    size.height / 2 + sin(angle).toFloat() * radius * .52f - radius * .29f),
                size = androidx.compose.ui.geometry.Size(radius * .58f, radius * .58f))
        }
        drawCircle(center, radius * .25f)
    }
}

/** 吉祥物插画使用透明背景，随场景切换迎接、学习或休息形象。 */
@Composable fun HimariPortrait(modifier: Modifier = Modifier, cropped: Boolean = false, artwork: HimariArtwork = HimariArtwork.Greeting) {
    Image(painterResource(artwork.drawable), contentDescription = "吉祥物：皆原阳茉莉",
        modifier = modifier, contentScale = if (cropped) ContentScale.Crop else ContentScale.Fit,
        alignment = Alignment.TopCenter)
}

@Composable fun HimariCompanionCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    artwork: HimariArtwork? = null,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .2f
    val visible = LocalHimariVisible.current
    val companion = LocalHimariMoment.current
    val shuffle = LocalHimariShuffle.current
    val actualArtwork = artwork ?: timedHimariArtwork()
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color.Transparent) {
        Row(Modifier.background(Brush.horizontalGradient(listOf(
            if (dark) Color(0xFF254843) else Color(0xFFDDEFE5),
            if (dark) Color(0xFF474032) else Color(0xFFFBE6C9)
        ))), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 18.dp, top = 17.dp, bottom = 17.dp, end = if (visible) 3.dp else 18.dp)) {
                Text(if (visible) label ?: "阳茉莉 · ${companion.part.label}陪伴" else "今日安排", color = colors.primary, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp)
                Text(title, color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 25.sp, modifier = Modifier.padding(top = 10.dp))
                Text(detail, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 6.dp))
                if (visible) {
                    Column(Modifier.padding(top = 8.dp).defaultMinSize(minHeight = 48.dp).then(if (shuffle != null)
                        Modifier.clickable(onClickLabel = "换一句阳茉莉的话", role = Role.Button, onClick = shuffle)
                        else Modifier).testTag("himari_quote")) {
                        Text(companion.line, color = colors.primary, fontSize = 11.sp, lineHeight = 18.sp,
                            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (shuffle != null) Text("点点我 · 换句话", color = colors.onSurfaceVariant.copy(alpha = .8f),
                            fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp))
                    }
                }
            }
            if (visible) Box(Modifier.width(138.dp).height(160.dp).clip(RoundedCornerShape(bottomEnd = 24.dp))
                .then(if (shuffle != null) Modifier.clickable(onClickLabel = if (artwork == null) "换一句阳茉莉的话与插画" else "换一句阳茉莉的话", role = Role.Button, onClick = shuffle)
                    else Modifier).testTag("himari_portrait_shuffle"), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.size(130.dp).align(Alignment.Center).background(colors.surface.copy(alpha = .45f), CircleShape))
                HimariFlower(Modifier.size(30.dp).align(Alignment.TopEnd).padding(3.dp))
                HimariFlower(Modifier.size(17.dp).align(Alignment.BottomStart).padding(2.dp))
                HimariPortrait(Modifier.fillMaxSize().padding(top = 5.dp, start = 2.dp, end = 2.dp),
                    cropped = actualArtwork in listOf(HimariArtwork.Greeting, HimariArtwork.Original, HimariArtwork.MorningWave,
                        HimariArtwork.EveningRead, HimariArtwork.MorningCoffee, HimariArtwork.NoonLibrary), artwork = actualArtwork)
            }
        }
    }
}

@Composable fun HimariEmptyState(title: String, detail: String, modifier: Modifier = Modifier,
    illustrationSize: Dp = 180.dp, action: @Composable () -> Unit = {}) {
    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (LocalHimariVisible.current) {
            Box(Modifier.size(illustrationSize), contentAlignment = Alignment.Center) {
                Box(Modifier.size(illustrationSize * .88f).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f), CircleShape))
                HimariFlower(Modifier.size(24.dp).align(Alignment.TopEnd))
                HimariPortrait(Modifier.size(illustrationSize - 2.dp), artwork = HimariArtwork.Study)
            }
            Spacer(Modifier.height(18.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            modifier = Modifier.padding(top = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action()
    }
}

@Composable fun HimariGallery(onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val entries = listOf("迎接" to HimariArtwork.Greeting, "学习" to HimariArtwork.Study,
        "休息" to HimariArtwork.Rest, "原画" to HimariArtwork.Original,
        "早晨·招呼" to HimariArtwork.MorningWave, "早晨·出发" to HimariArtwork.MorningBag,
        "午间·午饭" to HimariArtwork.NoonLunch, "午间·休息" to HimariArtwork.NoonBreak,
        "夜晚·阅读" to HimariArtwork.EveningRead, "夜晚·晚安" to HimariArtwork.EveningRest,
        "早晨·热饮" to HimariArtwork.MorningCoffee, "早晨·伸展" to HimariArtwork.MorningStretch,
        "午间·小仓鼠" to HimariArtwork.NoonHamster, "午间·图书馆" to HimariArtwork.NoonLibrary,
        "夜晚·星星" to HimariArtwork.EveningStars, "夜晚·挥手" to HimariArtwork.EveningGoodnight)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("阳茉莉的小小画册", style = MaterialTheme.typography.titleLarge)
                        Text("皆原阳茉莉 · 课间陪伴", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 5.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "关闭画册" }) {
                        Text("×", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(260.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), CircleShape))
                    HimariPortrait(Modifier.fillMaxSize().padding(vertical = 28.dp), artwork = entries[selected].second)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    entries.forEachIndexed { index, (label, _) ->
                        FilterChip(selected == index, onClick = { selected = index }, label = { Text(label) })
                    }
                }
                Text("皆原陽茉莉 / フレラバ ～Friend to Lover～", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp, modifier = Modifier.padding(top = 18.dp))
                Text("角色 © SMEE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            }
        }
    }
}
