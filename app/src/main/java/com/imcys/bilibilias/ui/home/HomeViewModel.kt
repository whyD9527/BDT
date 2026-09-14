package com.imcys.bilibilias.ui.home

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imcys.bilibilias.BuildConfig
import com.imcys.bilibilias.data.model.BILILoginUserModel
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.data.repository.QRCodeLoginRepository
import com.imcys.bilibilias.data.repository.RiskManagementRepository
import com.imcys.bilibilias.database.entity.BILIUsersEntity
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.datastore.AppSettingsSerializer
import com.imcys.bilibilias.datastore.source.UsersDataSource
import com.imcys.bilibilias.download.NewDownloadManager
import com.imcys.bilibilias.network.ApiStatus
import com.imcys.bilibilias.network.NetWorkResult
import com.imcys.bilibilias.network.emptyNetWorkResult
import com.imcys.bilibilias.network.model.app.AppUpdateConfigInfo
import com.imcys.bilibilias.network.model.app.BannerConfigInfo
import com.imcys.bilibilias.network.model.app.BulletinConfigInfo
import com.imcys.bilibilias.network.service.GitHubReleaseService
import com.imcys.bilibilias.network.utils.RemoteVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 更新说明最多展示多少字符（Release 正文可能很长，弹窗里放不下） */
private const val UPDATE_NOTES_MAX_LENGTH = 500

class HomeViewModel(
    private val qrCodeLoginRepository: QRCodeLoginRepository,
    private val usersDataSource: UsersDataSource,
    private val riskManagementRepository: RiskManagementRepository,
    private val downloadManager: NewDownloadManager,
    private val appSettingsRepository: AppSettingsRepository,
    private val gitHubReleaseService: GitHubReleaseService
) : ViewModel() {

    data class UIState(
        val fromLoginEventConsumed: Boolean = false,
        val shownAppUpdate: Boolean = false,
        val fetchedAppUpdateInfo: Boolean = false
    )

    val appSettings = appSettingsRepository.appSettingsFlow

    val appSettingsState = appSettingsRepository.appSettingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings.getDefaultInstance()
    )

    private var _uiState = MutableStateFlow(UIState())
    val uiState = _uiState.asStateFlow()

    private val _loginUserInfoState =
        MutableStateFlow<NetWorkResult<BILILoginUserModel?>>(emptyNetWorkResult())
    val loginUserInfoState = _loginUserInfoState.asStateFlow()

    private val _userLoginPlatformList =
        MutableStateFlow<List<BILIUsersEntity>>(emptyList())
    val userLoginPlatformList = _userLoginPlatformList.asStateFlow()

    val downloadListState = downloadManager.getAllDownloadTasks()


    private val _homeLayoutTypesetList = MutableStateFlow(emptyList<AppSettings.HomeLayoutItem>())

    var homeLayoutTypesetList = _homeLayoutTypesetList.asStateFlow()

    private val _bannerList = MutableStateFlow<List<BannerConfigInfo>>(emptyList())
    val bannerList = _bannerList.asStateFlow()


    private val _bulletinInfo = MutableStateFlow<BulletinConfigInfo?>(null)
    val bulletinInfo = _bulletinInfo.asStateFlow()

    private val _appUpdateInfo = MutableStateFlow<AppUpdateConfigInfo?>(null)
    val appUpdateInfo = _appUpdateInfo.asStateFlow()

    private val _useToolHistoryList = MutableStateFlow(toToolInfoList(AppSettingsSerializer.appSettingsDefault.useToolHistoryList))
    val useToolHistoryList = _useToolHistoryList.asStateFlow()

    // 使用工具历史列表
    init {
        initLayoutTypeset()
        initDownloadList()
        monitorUserState()
        updateWebSpi()
    }

    /**
     * 监听用户登录状态变化
     */
    private fun monitorUserState() {
        viewModelScope.launch(Dispatchers.IO) {
            usersDataSource.users.collect {
                if (it.currentUserId == 0L) {
                    _loginUserInfoState.value = emptyNetWorkResult()
                    _userLoginPlatformList.value = emptyList()
                } else {
                    showBILIUserInfo()
                }
            }
        }
    }

    /**
     * 检查本仓库是否有新版本（进首页时调一次）。
     *
     * 数据源是**我们自己的 GitHub Release**，取代了原先来自 `api.misakamoe.com`
     * 的更新检查 —— 那个域名已不在原作者控制下，且返回的 url 会直接变成
     * "立即更新"弹窗并打开任意地址（交接文档第七轮）。
     *
     * 行为约定（都不打扰用户）：
     * - 网络失败 / 被墙 / 限流 / 没有 Release → 静默返回；
     * - draft 与 prerelease 不提示；
     * - 版本不高于当前 → 不提示（比较逻辑见 [RemoteVersion]，有单测）；
     * - 跳转指向 **Release 页面**而不是直链 APK，让用户自己看清再下载。
     */
    fun checkForAppUpdate() {
        if (_uiState.value.fetchedAppUpdateInfo) return
        _uiState.value = _uiState.value.copy(fetchedAppUpdateInfo = true)

        viewModelScope.launch(Dispatchers.IO) {
            val release = gitHubReleaseService.getLatestRelease() ?: return@launch
            if (release.draft || release.prerelease) return@launch
            if (!RemoteVersion.isNewer(release.tagName, BuildConfig.VERSION_NAME)) return@launch

            val notes = release.body.trim().take(UPDATE_NOTES_MAX_LENGTH)
            _appUpdateInfo.value = AppUpdateConfigInfo(
                id = 30,
                version = release.tagName,
                url = release.htmlUrl.ifBlank { GitHubReleaseService.RELEASES_PAGE },
                feat = notes,
                fix = notes,
                remark = "",
                forcedUpdate = false,
                publishDateTime = release.publishedAt,
            )
        }
    }

    fun updateLastBulletinContent() {
        viewModelScope.launch {
            appSettingsRepository.updateLastBulletinContent(_bulletinInfo.value?.content ?: "")
        }
    }

    private fun toToolInfoList(toolNameList: List<String>): List<ToolInfo> {
        return toolNameList.mapNotNull { name ->
            runCatching {
                ToolInfo.valueOf(name)
            }.getOrNull()
        }.filter { info -> info.isScreen }
    }

    private fun initLayoutTypeset() {
        viewModelScope.launch {
            appSettingsRepository.asyncHomeLayoutTypesetList()
            appSettings.collect {
                _homeLayoutTypesetList.value = it.homeLayoutTypesetList
                _useToolHistoryList.value = toToolInfoList(it.useToolHistoryList)
            }

        }
    }

    private fun initDownloadList() {
        viewModelScope.launch(Dispatchers.IO) {
            downloadManager.initDownloadList()
        }
    }

    fun showBILIUserInfo() {
        viewModelScope.launch {
            initCurrentUserInfo()
        }
    }


    fun onNavigatedFromLogin() {
        viewModelScope.launch {
            if (loginUserInfoState.value.status != ApiStatus.SUCCESS) {
                showBILIUserInfo()
                _uiState.emit(uiState.value.copy(fromLoginEventConsumed = true))
            }
        }
    }


    fun updateWebSpi() {
        // 更新校验
        viewModelScope.launch(Dispatchers.IO) {
            riskManagementRepository.updateWebSpiCookie()
        }
    }

    /**
     * 暂停下载任务
     * [segmentId] 下载任务的ID
     */
    fun pauseDownloadTask(segmentId: Long) {
        viewModelScope.launch { downloadManager.pauseTask(segmentId) }
    }

    @SuppressLint("MissingPermission")
    fun resumeDownloadTask(segmentId: Long) {
        viewModelScope.launch { downloadManager.resumeTask(segmentId) }
    }

    fun cancelDownloadTask(segmentId: Long) {
        viewModelScope.launch { downloadManager.cancelTask(segmentId) }
    }

    suspend fun initCurrentUserInfo() {
        if (!usersDataSource.isLogin()) return
        val biliUserList =
            qrCodeLoginRepository.getBILIUserListByUid(usersDataSource.getUserId())
        if (biliUserList.isEmpty()) return

        val currentUser = biliUserList.first { it.id == usersDataSource.getUserId() }

        qrCodeLoginRepository.getLoginUserInfo(
            currentUser.loginPlatform,
            currentUser.accessToken
        )
            .collect {
                _loginUserInfoState.emit(it)

                // 更新本地记录
                when (it) {
                    is NetWorkResult.Success<*> -> {
                        biliUserList.forEach { user ->
                            qrCodeLoginRepository.updateBILIUser(
                                user.copy(
                                    mid = it.data?.mid ?: 0L,
                                    name = it.data?.name ?: "",
                                    face = it.data?.face ?: "",
                                    level = it.data?.level ?: 0,
                                    vipState = it.data?.vipState ?: 0,
                                )
                            )
                            _userLoginPlatformList.emit(biliUserList)
                        }
                    }

                    else -> {}
                }
            }


    }

    // 更新已经显示过
    fun onAppUpdateDialogShown() {
        viewModelScope.launch {
            _uiState.emit(uiState.value.copy(shownAppUpdate = true))
        }
    }

    // 更新使用工具记录
    fun updateUseToolRecord(tool: ToolInfo) {
        viewModelScope.launch {
            appSettingsRepository.updateUseToolRecord(tool.name)
        }
    }


}