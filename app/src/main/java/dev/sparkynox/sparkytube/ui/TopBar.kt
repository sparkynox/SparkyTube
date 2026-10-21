package dev.sparkynox.sparkytube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.sparkynox.sparkytube.R

class OverflowItem(val label: String, val icon: Int, val run: () -> Unit)

// painterResource only takes vectors and bitmaps; some of the menu icons are framework selectors and crashed it.
// an ImageView takes whatever drawable it is
@Composable
private fun ResIcon(res: Int, tint: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 24.dp) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
        update = { v ->
            v.setImageResource(res)
            v.imageTintList = android.content.res.ColorStateList.valueOf(tint.toArgb())
        },
        modifier = Modifier.size(size),
    )
}

@Composable
fun TopBar(
    showAudio: Boolean,
    showQuality: Boolean,
    onAudio: () -> Unit,
    onQuality: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onOverflow: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SparkyTube", Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val tint = MaterialTheme.colorScheme.onSurface
        if (showAudio) IconButton(onClick = onAudio) { ResIcon(R.drawable.ic_audio_track, tint) }
        if (showQuality) IconButton(onClick = onQuality) { ResIcon(R.drawable.ic_quality, tint) }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
        IconButton(onClick = onOverflow) { Icon(Icons.Filled.MoreVert, "More options") }
    }
}

// the quick-access grid that opens from the top bar's ⋮
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverflowSheet(items: List<OverflowItem>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items) { sc ->
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable { onDismiss(); sc.run() }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ResIcon(sc.icon, MaterialTheme.colorScheme.primary, 26.dp)
                    Text(
                        sc.label, Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
