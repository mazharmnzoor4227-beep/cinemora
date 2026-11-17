package com.cinemora.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinemora.app.data.Movie
import com.cinemora.app.ui.theme.*

@Composable
fun BrandHeader(
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Purple, Color(0xFF4D42B8)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text("CINEMORA", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
            Text("YOUR WORLD OF ENTERTAINMENT", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.1.sp)
        }
        Spacer(Modifier.weight(1f))
        RoundAction("⌕", onSearchClick)
        Spacer(Modifier.width(9.dp))
        RoundAction("●", onProfileClick)
    }
}

@Composable
private fun RoundAction(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(38.dp).clip(CircleShape).background(SurfaceElevated).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionHeading(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(action, color = PurpleLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onAction?.invoke() })
        }
    }
}

@Composable
fun CategoryChips(
    items: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            val active = item == selected
            Box(
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (active) Purple else SurfaceElevated)
                    .border(1.dp, if (active) PurpleLight else DividerColor, RoundedCornerShape(50))
                    .clickable { onSelected(item) }.padding(horizontal = 15.dp, vertical = 9.dp)
            ) {
                Text(item, color = if (active) Color.White else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun PosterCard(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(138.dp).clickable(onClick = onClick)) {
        PosterArtwork(movie = movie, modifier = Modifier.fillMaxWidth().height(190.dp))
        Spacer(Modifier.height(8.dp))
        Text(movie.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${movie.year}  ·  ${movie.genre}", color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PosterArtwork(movie: Movie, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(listOf(movie.accentStart, movie.accentEnd))),
    ) {
        if (movie.posterUrl != null) {
            AsyncImage(
                model = movie.posterUrl,
                contentDescription = movie.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
        } else {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(76.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.09f)),
            )
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(42.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.07f)),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(movie.label, color = Color.White.copy(alpha = 0.78f), fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
            Text(movie.title.uppercase(), color = Color.White, fontSize = 17.sp, lineHeight = 18.sp, fontWeight = FontWeight.Black, maxLines = 3)
            Text(movie.genre.uppercase(), color = Color.White.copy(alpha = 0.75f), fontSize = 8.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun ContinueCard(movie: Movie, onClick: () -> Unit) {
    Row(
        modifier = Modifier.width(245.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceNight)
            .clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PosterArtwork(movie, Modifier.width(92.dp).height(70.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(movie.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (movie.type == "Series" || movie.type == "K-Drama") "S1 · Continue episode" else "Continue watching", color = TextMuted, fontSize = 9.sp)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(DividerColor)) {
                Box(Modifier.fillMaxWidth(movie.progress.coerceAtLeast(0.12f)).height(4.dp).clip(CircleShape).background(PurpleLight))
            }
            Text("${(movie.progress * 100).toInt()}% watched", color = TextMuted, fontSize = 9.sp)
        }
    }
}

@Composable
fun SmallTag(text: String, color: Color = Purple) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(text, color = PurpleLight, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    }
}
