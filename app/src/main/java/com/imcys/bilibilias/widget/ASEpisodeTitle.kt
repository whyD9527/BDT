package com.imcys.bilibilias.widget

import androidx.compose.ui.res.stringResource
import com.imcys.bilibilias.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.ui.widget.ASIconButton

typealias OnUpdateEpisodeListMode = (AppSettings.EpisodeListMode) -> Unit

@Composable
fun ASEpisodeTitle(
    title: String,
    isSelectSingleModel: Boolean = true,
    episodeListMode: AppSettings.EpisodeListMode,
    onSelectAllClick: () -> Unit = {},
    onUpdateEpisodeListMode: OnUpdateEpisodeListMode
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title)
        Spacer(Modifier.weight(1f))

        AnimatedVisibility(!isSelectSingleModel) {
            ASIconButton(
                shape = CircleShape,
                onClick = onSelectAllClick
            ) {
                Icon(
                    Icons.Outlined.SelectAll,
                    contentDescription = stringResource(R.string.cd_select_all),
                )
            }
        }


        ASIconButton(
            shape = CircleShape,
            onClick = {
                onUpdateEpisodeListMode.invoke(
                    if (episodeListMode == AppSettings.EpisodeListMode.EpisodeListMode_List) {
                        AppSettings.EpisodeListMode.EpisodeListMode_Grid
                    } else {
                        AppSettings.EpisodeListMode.EpisodeListMode_List
                    }
                )
            }
        ) {
            when (episodeListMode) {
                AppSettings.EpisodeListMode.UNRECOGNIZED,
                AppSettings.EpisodeListMode.EpisodeListMode_Grid -> {
                    Icon(
                        Icons.Outlined.Apps,
                        contentDescription = stringResource(R.string.cd_view_grid),
                    )
                }

                AppSettings.EpisodeListMode.EpisodeListMode_List -> {
                    Icon(
                        Icons.AutoMirrored.Outlined.List,
                        contentDescription = stringResource(R.string.cd_view_list),
                    )
                }
            }
        }
    }
}