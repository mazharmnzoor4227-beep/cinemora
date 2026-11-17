package com.cinemora.app.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cinemora.app.core.AnimeDetails
import com.cinemora.app.core.EpisodeItem
import com.cinemora.app.core.Lang
import com.cinemora.app.data.Movie
import com.cinemora.app.player.PlayerViewModel
import com.cinemora.app.ui.theme.*
import kotlinx.coroutines.delay

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@UnstableApi
@Composable
fun PlayerScreen(
    movie: Movie,
    episode: EpisodeItem,
    details: AnimeDetails?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: PlayerViewModel = viewModel()
    val sources by viewModel.sources.collectAsState()
    val currentSourceIndex by viewModel.currentSourceIndex.collectAsState()
    val subtitles by viewModel.subtitles.collectAsState()
    val captionsEnabled by viewModel.captionsEnabled.collectAsState()
    val preferredSubtitleLang by viewModel.preferredSubtitleLang.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val opRange by viewModel.opRange.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val currentMovie by viewModel.currentMovie.collectAsState()

    var holding by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var openSetting by remember { mutableStateOf<String?>(null) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var dragBrightness by remember { mutableFloatStateOf(-1f) }
    var dragVolume by remember { mutableFloatStateOf(-1f) }
    var dragIsLeft by remember { mutableStateOf(true) }

    LaunchedEffect(movie.id, episode.id) {
        viewModel.play(context, movie, episode, details)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.releaseAndSave() }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(gestureText) {
        if (gestureText != null) {
            delay(1200)
            gestureText = null
        }
    }

    val showSkipIntro = when {
        opRange != null -> positionMs in (opRange!!.startMs - 2000)..opRange!!.endMs
        durationMs > 0 -> positionMs < 90_000
        else -> false
    }
    val qualityLabel = sources.getOrNull(currentSourceIndex)?.let { src ->
        if (src.quality > 0) "${src.quality}p" else "Auto"
    } ?: "Auto"
    val audioLabel = when {
        (currentMovie ?: movie).genre.contains("Hindi", ignoreCase = true) -> "Hindi Dub"
        (currentMovie ?: movie).genre.contains("Japanese", ignoreCase = true) -> "Japanese"
        else -> "English"
    }
    val subtitleLabel = when {
        !captionsEnabled -> "Off"
        preferredSubtitleLang != null -> preferredSubtitleLang!!
        subtitles.isNotEmpty() -> subtitles.first().label
        else -> "English"
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SurfaceElevated).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(movie.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("EPISODE ${episode.number} · CINEMORA PLAYER", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.sp)
            }
            Icon(Icons.Default.Settings, contentDescription = "Player settings", tint = TextMuted, modifier = Modifier.clickable { openSetting = "Quality" }.size(24.dp))
        }

        Spacer(Modifier.height(18.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.Black),
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = viewModel.player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = { it.player = viewModel.player },
                modifier = Modifier.fillMaxSize(),
            )
            // Gesture layer: tap = toggle controls, vertical drag = brightness (left) / volume (right).
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                dragIsLeft = offset.x < widthPx / 2f
                                val activity = context as? Activity
                                if (dragIsLeft) {
                                    val attrs = activity?.window?.attributes
                                    dragBrightness = attrs?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                                } else {
                                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                    val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                                    val cur = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                                    dragVolume = if (max > 0) cur.toFloat() / max else 0f
                                }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                val activity = context as? Activity
                                if (dragIsLeft) {
                                    val next = (dragBrightness - dragAmount / 400f).coerceIn(0.05f, 1f)
                                    dragBrightness = next
                                    activity?.window?.let { window ->
                                        val attrs = window.attributes
                                        attrs.screenBrightness = next
                                        window.attributes = attrs
                                    }
                                    gestureText = "Brightness ${(next * 100).toInt()}%"
                                } else {
                                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                    val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                                    val next = (dragVolume - dragAmount / 400f).coerceIn(0f, 1f)
                                    dragVolume = next
                                    am?.setStreamVolume(AudioManager.STREAM_MUSIC, (next * max).toInt(), 0)
                                    gestureText = "Volume ${(next * 100).toInt()}%"
                                }
                            },
                        )
                    },
            )
            if (isLoading && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PurpleLight)
                }
            }
            if (error != null) {
                Column(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error!!, color = TextPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.play(context, movie, episode, details) },
                        colors = ButtonDefaults.buttonColors(containerColor = Purple),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Retry", fontWeight = FontWeight.Bold) }
                }
            }
            if (gestureText != null) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(gestureText!!, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            if (message != null) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(message!!, color = Color.White, fontSize = 12.sp)
                }
            }
            if (holding) {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("2× SPEED", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            if (controlsVisible && showSkipIntro) {
                Button(
                    onClick = { viewModel.skipIntro() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Skip Intro", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
            if (controlsVisible) {
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(58.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.13f))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                holding = true
                                viewModel.setHold2x(true)
                                tryAwaitRelease()
                                holding = false
                                viewModel.setHold2x(false)
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("2×", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
                if (!isPlaying && error == null) {
                    Box(
                        Modifier.align(Alignment.Center).size(74.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { viewModel.togglePlay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(42.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Slider(
            value = fraction,
            onValueChange = { viewModel.seekTo((it * durationMs).toLong()) },
            colors = SliderDefaults.colors(thumbColor = PurpleLight, activeTrackColor = Purple, inactiveTrackColor = DividerColor),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(positionMs), color = TextMuted, fontSize = 10.sp)
            Text(formatMs(durationMs), color = TextMuted, fontSize = 10.sp)
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds", tint = TextPrimary, modifier = Modifier.size(28.dp).clickable { viewModel.seekBy(-10_000) })
            Box(Modifier.size(58.dp).clip(CircleShape).background(Purple).clickable { viewModel.togglePlay() }, contentAlignment = Alignment.Center) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds", tint = TextPrimary, modifier = Modifier.size(28.dp).clickable { viewModel.seekBy(10_000) })
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceNight).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PLAYBACK SETTINGS", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerSettingButton("Speed", "${if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()}x", Icons.Default.Settings, Modifier.weight(1f)) { openSetting = "Speed" }
                PlayerSettingButton("Quality", qualityLabel, Icons.Default.HighQuality, Modifier.weight(1f)) { openSetting = "Quality" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerSettingButton("Audio", audioLabel, Icons.Default.Language, Modifier.weight(1f)) { openSetting = "Audio" }
                PlayerSettingButton("Subtitles", subtitleLabel, Icons.Default.ClosedCaption, Modifier.weight(1f)) { openSetting = "Subtitles" }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Swipe up/down on the left side for brightness, right side for volume. Hold 2× for double speed.", color = TextMuted, fontSize = 9.sp, lineHeight = 13.sp)
    }

    if (openSetting != null) {
        val title = openSetting!!
        val options: List<String> = when (title) {
            "Speed" -> listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
            "Quality" -> listOf("Auto (best)") + sources.mapIndexed { index, src ->
                val q = if (src.quality > 0) "${src.quality}p" else "Unknown quality"
                "$q · ${src.serverName}"
            }
            "Audio" -> listOf("Hindi Dub", "English", "Japanese")
            else -> listOf("Off") + subtitles.map { it.label }
        }
        val selectedValue: String = when (title) {
            "Speed" -> "${if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()}x"
            "Quality" -> if (currentSourceIndex == 0) "Auto (best)" else sources.getOrNull(currentSourceIndex)?.let { src ->
                val q = if (src.quality > 0) "${src.quality}p" else "Unknown quality"
                "$q · ${src.serverName}"
            } ?: "Auto (best)"
            "Audio" -> audioLabel
            else -> if (!captionsEnabled) "Off" else subtitleLabel
        }
        AlertDialog(
            onDismissRequest = { openSetting = null },
            containerColor = SurfaceNight,
            title = { Text("Choose $title", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    options.forEachIndexed { index, option ->
                        val selected = option == selectedValue ||
                            (title == "Quality" && index == 0 && currentSourceIndex == 0)
                        Text(
                            text = (if (selected) "✓  " else "     ") + option,
                            color = if (selected) PurpleLight else TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                when (title) {
                                    "Speed" -> viewModel.setSpeed(option.removeSuffix("x").toFloatOrNull() ?: 1f)
                                    "Quality" -> viewModel.selectSource(context, if (index == 0) 0 else index - 1)
                                    "Audio" -> {
                                        val target = when (option) {
                                            "Hindi Dub" -> Lang.HINDI
                                            "Japanese" -> Lang.JAPANESE
                                            else -> Lang.ENGLISH
                                        }
                                        viewModel.switchAudio(context, target)
                                    }
                                    else -> {
                                        if (option == "Off") {
                                            viewModel.setCaptionsEnabled(false)
                                        } else {
                                            viewModel.setCaptionsEnabled(true)
                                            val sub = subtitles.firstOrNull { it.label == option }
                                            viewModel.setPreferredSubtitleLang(sub?.language)
                                        }
                                    }
                                }
                                openSetting = null
                            }.padding(vertical = 9.dp, horizontal = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { openSetting = null }) { Text("Done", color = PurpleLight) } },
        )
    }
}

@Composable
private fun PlayerSettingButton(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(SurfaceElevated).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = PurpleLight, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = TextMuted, fontSize = 9.sp)
            Text(value, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
