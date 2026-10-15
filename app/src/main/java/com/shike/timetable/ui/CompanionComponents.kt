package com.shike.timetable.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shike.timetable.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable fun ThemeFlower(modifier: Modifier = Modifier) {
    val petals = MaterialTheme.colorScheme.surface
    val center = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        val radius = size.minDimension / 2
        repeat(8) { i ->
            val angle = i * Math.PI / 4
            drawOval(petals, topLeft = Offset(size.width / 2 + cos(angle).toFloat() * radius * .52f - radius * .29f,
                size.height / 2 + sin(angle).toFloat() * radius * .52f - radius * .29f),
                size = androidx.compose.ui.geometry.Size(radius * .58f, radius * .58f))
        }
        drawCircle(center, radius * .25f)
    }
}

@Composable fun CompanionPortrait(modifier: Modifier = Modifier, artwork: ThemeArtwork? = null,
    scene: String = "greeting", cropped: Boolean? = null) {
    val theme = LocalAppTheme.current
    val content = theme.companion ?: return
    val actual = artwork ?: content.scene(scene)
    val context = LocalContext.current
    val bitmap = remember(theme.id, theme.version, actual.file) { ThemeImages.load(context, theme, actual) } ?: return
    Image(bitmap.asImageBitmap(), contentDescription = "吉祥物：${content.name}", modifier = modifier,
        contentScale = if (cropped ?: actual.crop) ContentScale.Crop else ContentScale.Fit, alignment = Alignment.TopCenter)
}

@Composable fun CompanionCard(title: String, detail: String, modifier: Modifier = Modifier, label: String? = null, scene: String? = null) {
    val theme = LocalAppTheme.current
    val content = theme.companion
    val colors = MaterialTheme.colorScheme
    val palette = theme.palette(LocalDarkTheme.current)
    val visible = LocalCompanionVisible.current
    val companion = LocalCompanionMoment.current
    val shuffle = LocalCompanionShuffle.current
    val voicePhase = LocalVoicePhase.current
    val actual = if (scene != null) content?.scene(scene) else content?.artwork(companion?.artwork.orEmpty())
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color.Transparent) {
        Row(Modifier.background(Brush.horizontalGradient(listOf(Color(palette.color("cardStart")), Color(palette.color("cardEnd"))))), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 18.dp, top = 17.dp, bottom = 17.dp, end = if (visible) 3.dp else 18.dp)) {
                Text(if (visible) label ?: "${content?.shortName} · ${companion?.part?.label.orEmpty()}陪伴" else "今日安排",
                    color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp)
                Text(title, color = colors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp, modifier = Modifier.padding(top = 10.dp))
                Text(detail, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
                if (visible && companion != null) {
                    Column(Modifier.padding(top = 8.dp).defaultMinSize(minHeight = 48.dp)
                        .then(if (shuffle != null) Modifier.clickable(enabled = voicePhase == null, onClickLabel = "换一句${content?.shortName}的话", role = Role.Button, onClick = shuffle) else Modifier)
                        .testTag("companion_quote")) {
                        Text(companion.line, color = colors.primary, fontSize = 11.sp, lineHeight = 18.sp,
                            maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (voicePhase != null) Text(voicePhase.label, color = colors.onSurfaceVariant,
                            fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp).testTag("voice_status"))
                        if (voicePhase == null && shuffle != null && LocalShuffleHint.current) Text("点点我 · 换句话", color = colors.onSurfaceVariant.copy(alpha = .8f),
                            fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp, bottom = 4.dp).testTag("shuffle_hint"))
                    }
                }
            }
            if (visible) Box(Modifier.width(138.dp).height(160.dp).clip(RoundedCornerShape(bottomEnd = 24.dp))
                .then(if (shuffle != null) Modifier.clickable(enabled = voicePhase == null, onClickLabel = "换一句${content?.shortName}的话", role = Role.Button, onClick = shuffle) else Modifier)
                .testTag("companion_portrait_shuffle"), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.size(130.dp).align(Alignment.Center).background(colors.surface.copy(alpha = .45f), CircleShape))
                ThemeFlower(Modifier.size(30.dp).align(Alignment.TopEnd).padding(3.dp))
                ThemeFlower(Modifier.size(17.dp).align(Alignment.BottomStart).padding(2.dp))
                CompanionPortrait(Modifier.fillMaxSize().padding(top = 5.dp, start = 2.dp, end = 2.dp), artwork = actual)
            }
        }
    }
}

@Composable fun CompanionEmptyState(title: String, detail: String, modifier: Modifier = Modifier,
    illustrationSize: Dp = 180.dp, action: @Composable () -> Unit = {}) {
    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (LocalCompanionVisible.current) {
            Box(Modifier.size(illustrationSize), contentAlignment = Alignment.Center) {
                Box(Modifier.size(illustrationSize * .88f).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f), CircleShape))
                ThemeFlower(Modifier.size(24.dp).align(Alignment.TopEnd))
                CompanionPortrait(Modifier.size(illustrationSize - 2.dp), scene = "empty")
            }
            Spacer(Modifier.height(18.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            modifier = Modifier.padding(top = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action()
    }
}

@Composable fun CompanionGallery(onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val content = theme.companion ?: return
    var selectedId by rememberSaveable(theme.id) { mutableStateOf(content.artworks.first().id) }
    val selected = content.artwork(selectedId) ?: content.artworks.first()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${content.shortName}的小小画册", style = MaterialTheme.typography.titleLarge)
                        Text("${content.name} · ${content.artworks.size} 幅插画", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "关闭画册" }) {
                        Text("×", fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(260.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), CircleShape))
                    CompanionPortrait(Modifier.fillMaxSize().padding(vertical = 28.dp), artwork = selected, cropped = false)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    content.artworks.forEach { art -> FilterChip(selected.id == art.id, onClick = { selectedId = art.id }, label = { Text(art.label) }) }
                }
                Text(content.attribution, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 18.dp, bottom = 12.dp))
            }
        }
    }
}
