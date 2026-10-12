package com.tunenagi.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.tunenagi.core.designsystem.TunenagiSpacing
import com.tunenagi.player.R
import com.tunenagi.player.ui.shell.TunenagiEmptyState

const val SEARCH_SCREEN_TAG = "screen-search"
const val SEARCH_FIELD_TAG = "search-field"

/**
 * Search.
 *
 * The query field is real state that survives rotation; the results are not wired yet
 * because there is nothing to search — no local library and no connected source. The
 * screen says so instead of showing fabricated results or a spinner that never resolves.
 * Debounce, cancellation, filters and paging arrive with the provider phases.
 */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(TunenagiSpacing.LgDp.dp)
            .testTag(SEARCH_SCREEN_TAG)
    ) {
        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TunenagiSpacing.MdDp.dp)
                .testTag(SEARCH_FIELD_TAG),
            singleLine = true,
            label = { Text(stringResource(R.string.search_label)) },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        TunenagiEmptyState(
            title = stringResource(R.string.search_empty_title),
            message = stringResource(R.string.search_empty_message),
            modifier = Modifier.weight(1f)
        )
    }
}
