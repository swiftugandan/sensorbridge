package dev.sensorbridge.tilt.ui.levels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sensorbridge.tilt.R
import dev.sensorbridge.tilt.data.Progress
import dev.sensorbridge.tilt.engine.catalog.LevelCatalog
import dev.sensorbridge.tilt.engine.catalog.LevelEntry
import dev.sensorbridge.tilt.ui.components.Stars
import dev.sensorbridge.tilt.ui.components.formatSeconds
import dev.sensorbridge.tilt.ui.home.ProgressViewModel
import dev.sensorbridge.tilt.ui.theme.NumericStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelsScreen(viewModel: ProgressViewModel, onPlay: (String) -> Unit, onBack: () -> Unit) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.levels)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        val p = progress ?: return@Scaffold
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 76.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            section(R.string.chapter_training, LevelCatalog.training, p, onPlay)
            section(R.string.chapter_labyrinths, LevelCatalog.labyrinths, p, onPlay)
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.section(
    title: Int,
    entries: List<LevelEntry>,
    progress: Progress,
    onPlay: (String) -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "header-$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
    }
    items(entries, key = { it.id }) { entry -> LevelTile(entry, progress, onPlay) }
}

@Composable
private fun LevelTile(entry: LevelEntry, progress: Progress, onPlay: (String) -> Unit) {
    val unlocked = progress.isUnlocked(entry)
    val record = progress.record(entry)
    val description = stringResource(
        R.string.level_tile_description,
        entry.number,
        when {
            !unlocked -> stringResource(R.string.locked)
            record == null -> stringResource(R.string.not_played)
            else -> pluralStringResource(R.plurals.stars, record.stars, record.stars) + ", " + formatSeconds(record.bestSeconds)
        },
    )
    Surface(
        onClick = { onPlay(entry.id) },
        enabled = unlocked,
        shape = RoundedCornerShape(16.dp),
        color = if (record != null) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .aspectRatio(0.82f)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!unlocked) disabled()
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!unlocked) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(entry.number.toString(), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Stars(record?.stars ?: 0, size = 14.dp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        record?.let { formatSeconds(it.bestSeconds) } ?: " ",
                        style = MaterialTheme.typography.labelSmall.merge(NumericStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
