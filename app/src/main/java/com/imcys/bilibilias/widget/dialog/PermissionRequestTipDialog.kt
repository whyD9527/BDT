package com.imcys.bilibilias.widget.dialog

import androidx.compose.ui.res.stringResource
import com.imcys.bilibilias.R
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.imcys.bilibilias.ui.widget.ASTextButton

@Composable
fun PermissionRequestTipDialog(
    show: Boolean,
    icon: @Composable (() -> Unit)? = {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = stringResource(R.string.cd_warning)
        )
    },
    @StringRes titleRes: Int = R.string.permission_dialog_title,
    message: String,
    @StringRes confirmTextRes: Int = R.string.permission_dialog_continue,
    @StringRes dismissTextRes: Int = R.string.common_cancel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = icon,
            title = { Text(stringResource(titleRes)) },
            text = { Text(message) },
            confirmButton = {
                ASTextButton(onClick = onConfirm) {
                    Text(stringResource(confirmTextRes))
                }
            },
            dismissButton = {
                ASTextButton(onClick = onDismiss) {
                    Text(stringResource(dismissTextRes))
                }
            }
        )
    }
}