package com.imcys.bilibilias.ui.setting.contract

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.R
import com.imcys.bilibilias.database.entity.download.FileNamePlaceholder
import com.imcys.bilibilias.database.entity.download.donghuaNamingRules
import com.imcys.bilibilias.database.entity.download.videoNamingRules
import com.imcys.bilibilias.datastore.AppSettingsSerializer
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.ui.widget.tip.ASInfoTip
import com.imcys.bilibilias.ui.widget.tip.ASWarringTip
import com.imcys.bilibilias.widget.maybeNestedScroll
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

@Serializable
data object NamingConventionRoute : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamingConventionScreen(
    namingConventionRoute: NamingConventionRoute,
    onToBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    NamingConventionScaffold(
        scrollBehavior = scrollBehavior,
        onToBack
    ) { paddingValues ->
        NamingConventionContent(
            modifier = Modifier
                .maybeNestedScroll(scrollBehavior)
                .padding(paddingValues),
            onToBack = onToBack
        )
    }
}

@Composable
fun LazyItemScope.NamingRuleEditor(
    title: String,
    placeholderList: List<FileNamePlaceholder>,
    ruleValue: String,
    defaultRule: String,
    onRuleChange: (String) -> Unit,
    onRestoreDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(ruleValue, selection = TextRange(ruleValue.length))) }
    LaunchedEffect(ruleValue) {
        if (ruleValue != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = ruleValue)
        }
    }

    Surface(
        shape = CardDefaults.shape,
        modifier = modifier
            .fillMaxWidth()
            .animateItem()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                placeholderList.forEach { item ->
                    AssistChip(onClick = {
                        onRuleChange(
                            if (ruleValue.isEmpty()) {
                                ruleValue + item.placeholder
                            } else {
                                ruleValue + "_" + item.placeholder
                            }
                        )
                        val newText = if (ruleValue.isEmpty()) {
                            ruleValue + item.placeholder
                        } else {
                            ruleValue + "_" + item.placeholder
                        }
                        // Move the cursor to the end after inserting a placeholder.
                        textFieldValue = TextFieldValue(newText, selection = TextRange(newText.length))
                    }, label = {
                        Text(
                            // F（2026-10-02 真机复验）：`item.description` 是 core:database 里的**硬编码中文**
                            // （`视频标题/分P标题/…`），英文界面下这排占位符标签就漏中文。改成按**占位符 token**
                            // （`{title}` 这类，稳定标识）做 UI 层本地化映射；token 本身不变。
                            "${
                                item.placeholder.replace("{", "").replace("}", "")
                            }：${namingPlaceholderLabel(item.placeholder)}"
                        )
                    })
                }
            }
            AnimatedVisibility(ruleValue != defaultRule) {
                ASWarringTip {
                    Text(stringResource(R.string.setting_naming_warning))
                }
            }
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    onRuleChange(it.text)
                },
                label = { Text(stringResource(R.string.setting_naming_convention)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (!it.isFocused && ruleValue.isEmpty()) {
                            onRestoreDefault()
                        }
                    }
            )
            AnimatedVisibility(ruleValue != defaultRule) {
                Button(
                    shape = CardDefaults.shape,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRestoreDefault
                ) {
                    Text(stringResource(R.string.naming_restore_default))
                }
            }
        }
    }
}

@Composable
fun NamingConventionContent(
    modifier: Modifier = Modifier,
    onToBack: () -> Unit = {},
) {
    val vm = koinInject<NamingConventionViewModel>()
    var videoNamingRule by remember {
        mutableStateOf(AppSettingsSerializer.appSettingsDefault.videoNamingRule)
    }
    var donghuaNamingRule by remember {
        mutableStateOf(AppSettingsSerializer.appSettingsDefault.bangumiNamingRule)
    }

    LaunchedEffect(Unit) {
        vm.appSettings.collect {
            videoNamingRule = it.videoNamingRule
            donghuaNamingRule = it.bangumiNamingRule

            if (videoNamingRule.isEmpty()) {
                videoNamingRule = AppSettingsSerializer.appSettingsDefault.videoNamingRule
                vm.updateVideoNamingRule(videoNamingRule)
            }
            if (donghuaNamingRule.isEmpty()) {
                donghuaNamingRule = AppSettingsSerializer.appSettingsDefault.bangumiNamingRule
                vm.updateDonghuaNamingRule(donghuaNamingRule)
            }

        }
    }

    LazyColumn(
        modifier = modifier
            .padding(vertical = 5.dp, horizontal = 10.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            ASInfoTip {
                Text(
                    text = stringResource(R.string.naming_info),
                )
            }
        }
        item {
            ASWarringTip {
                Text(
                    text = stringResource(R.string.naming_warning),
                )
            }
        }
        item {
            NamingRuleEditor(
                title = stringResource(R.string.naming_video_placeholders_title),
                placeholderList = videoNamingRules,
                ruleValue = videoNamingRule,
                defaultRule = AppSettingsSerializer.appSettingsDefault.videoNamingRule,
                onRuleChange = {
                    vm.updateVideoNamingRule(it)
                },
                onRestoreDefault = {
                    vm.updateVideoNamingRule(AppSettingsSerializer.appSettingsDefault.videoNamingRule)
                }
            )
        }
        item {
            NamingRuleEditor(
                title = stringResource(R.string.naming_bangumi_placeholders_title),
                placeholderList = donghuaNamingRules,
                ruleValue = donghuaNamingRule,
                defaultRule = AppSettingsSerializer.appSettingsDefault.bangumiNamingRule,
                onRuleChange = {
                    vm.updateDonghuaNamingRule(it)
                },
                onRestoreDefault = {
                    vm.updateDonghuaNamingRule(AppSettingsSerializer.appSettingsDefault.bangumiNamingRule)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamingConventionScaffold(
    scrollBehavior: TopAppBarScrollBehavior,
    onToBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            ASTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scrollBehavior,
                style = BILIBILIASTopAppBarStyle.Large,
                title = {
                    Text(text = stringResource(R.string.setting_naming_convention))
                },
                navigationIcon = {
                    AsBackIconButton(onClick = {
                        onToBack.invoke()
                    })
                },
                alwaysDisplay = false
            )
        },
    ) {
        content(it)
    }

}

/** F：命名规则占位符的本地化标签（按 token 映射；未知 token 原样回退，不硬编码中文）。 */
@Composable
private fun namingPlaceholderLabel(placeholder: String): String = stringResource(
    when (placeholder) {
        "{title}" -> R.string.naming_ph_title
        "{p_title}" -> R.string.naming_ph_p_title
        "{author}" -> R.string.naming_ph_author
        "{p}" -> R.string.naming_ph_p
        "{aid}" -> R.string.naming_ph_aid
        "{bvid}" -> R.string.naming_ph_bvid
        "{cid}" -> R.string.naming_ph_cid
        "{collection_title}" -> R.string.naming_ph_collection_title
        "{season_title}" -> R.string.naming_ph_season_title
        "{episode_number}" -> R.string.naming_ph_episode_number
        "{episode_title}" -> R.string.naming_ph_episode_title
        else -> R.string.naming_ph_title
    }
)
