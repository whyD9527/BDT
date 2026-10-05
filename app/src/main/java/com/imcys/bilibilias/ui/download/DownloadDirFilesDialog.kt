package com.imcys.bilibilias.ui.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.imcys.bilibilias.R
import com.imcys.bilibilias.common.utils.StorageUtil
import com.imcys.bilibilias.data.download.record.DownloadDirFilesRules
import com.imcys.bilibilias.download.FileOutputManager

/**
 * 「下载目录文件」对话框：列出下载目录（新目录 + 旧目录）里的文件，
 * 「孤儿」= 没有任何下载记录指向它。
 *
 * 直接解决两个真实现象：重装后记录没了、文件还在；改名后旧文件成了没人管的孤儿。
 *
 * 2026-10-05（A-② / 剩余条目 #2）：原来这张清单长在**设置 → 存储管理**里，
 * 与下载管理的"重复检查"同源（都按名字扫下载目录）却分成两处 ——
 * 用户想弄清"目录里这个不认识的文件是谁的"，得先猜到要去设置里找。
 * 现在并入下载管理的同一张卡片，设置里的存储页只留"空间占用 + 清缓存"。
 */
@Composable
internal fun DownloadDirFilesDialog(
    files: List<FileOutputManager.DownloadFileEntry>,
    knownNames: Set<String>,
    onOpen: (FileOutputManager.DownloadFileEntry) -> Unit,
    onDelete: (FileOutputManager.DownloadFileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = CardDefaults.shape) {
            Column(Modifier.padding(12.dp)) {
                val summary = DownloadDirFilesRules.summarize(
                    dirFileDisplayNames = files.map { it.displayName },
                    referencedNames = knownNames,
                )
                Text(
                    stringResource(R.string.storage_download_files_title, summary.total, summary.orphan),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text(stringResource(R.string.storage_dir_empty))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(files, key = { it.uriString }) { file ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.displayName, fontSize = 12.sp, maxLines = 2)
                                    Text(
                                        text = StorageUtil.formatSize(file.sizeBytes) +
                                            if (DownloadDirFilesRules.isOrphan(file.displayName, knownNames)) {
                                                " " + stringResource(R.string.storage_orphan)
                                            } else {
                                                " " + stringResource(R.string.storage_has_record)
                                            },
                                        fontSize = 10.sp,
                                    )
                                }
                                TextButton(onClick = { onOpen(file) }) {
                                    Text(stringResource(R.string.storage_open))
                                }
                                TextButton(onClick = { onDelete(file) }) {
                                    Text(stringResource(R.string.common_delete))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_close)) }
                }
            }
        }
    }
}
