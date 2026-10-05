package com.imcys.bilibilias.navigation

import com.imcys.bilibilias.ui.setting.feedback.FeedbackRoute
import com.imcys.bilibilias.ui.setting.feedback.FeedbackScreen
import androidx.compose.ui.res.stringResource
import com.imcys.bilibilias.R
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.imcys.bilibilias.common.event.analysisHandleChannel
import com.imcys.bilibilias.common.event.playVoucherErrorChannel
import com.imcys.bilibilias.common.event.requestFrequentHandleChannel
import com.imcys.bilibilias.common.event.StartTarget
import com.imcys.bilibilias.common.event.startTargetChannel
import com.imcys.bilibilias.ui.analysis.AnalysisScreen
import com.imcys.bilibilias.ui.analysis.AnalysisViewModel
import com.imcys.bilibilias.ui.analysis.navigation.AnalysisRoute
import com.imcys.bilibilias.ui.analysis.videocodeing.VideoCodingInfoRoute
import com.imcys.bilibilias.ui.analysis.videocodeing.VideoCodingInfoScreen
import com.imcys.bilibilias.ui.download.DownloadScreen
import com.imcys.bilibilias.ui.download.navigation.DownloadRoute
import com.imcys.bilibilias.ui.event.playvoucher.PlayVoucherErrorPage
import com.imcys.bilibilias.ui.event.playvoucher.navigation.PlayVoucherErrorRoute
import com.imcys.bilibilias.ui.event.requestFrequent.RequestFrequentRoute
import com.imcys.bilibilias.ui.event.requestFrequent.RequestFrequentScreen
import com.imcys.bilibilias.ui.home.HomeScreen
import com.imcys.bilibilias.ui.home.navigation.HomeRoute
import com.imcys.bilibilias.ui.login.LoginScreen
import com.imcys.bilibilias.ui.login.navigation.LoginRoute
import com.imcys.bilibilias.ui.setting.SettingScreen
import com.imcys.bilibilias.ui.setting.about.AboutRouter
import com.imcys.bilibilias.ui.setting.about.AboutScreen
import com.imcys.bilibilias.ui.setting.contract.NamingConventionRoute
import com.imcys.bilibilias.ui.setting.contract.NamingConventionScreen
import com.imcys.bilibilias.ui.setting.developer.LineConfigRoute
import com.imcys.bilibilias.ui.setting.developer.LineConfigScreen
import com.imcys.bilibilias.ui.setting.layout.LayoutTypesetRoute
import com.imcys.bilibilias.ui.setting.layout.LayoutTypesetScreen
import com.imcys.bilibilias.ui.setting.navigation.SettingRoute
import com.imcys.bilibilias.ui.setting.platform.ParsePlatformRoute
import com.imcys.bilibilias.ui.setting.platform.ParsePlatformScreen
import com.imcys.bilibilias.ui.setting.storage.StorageManagementRoute
import com.imcys.bilibilias.ui.setting.storage.StorageManagementScreen
import com.imcys.bilibilias.ui.tools.frame.FrameExtractorRoute
import com.imcys.bilibilias.ui.tools.frame.FrameExtractorScreen
import com.imcys.bilibilias.ui.tools.parser.WebParserRoute
import com.imcys.bilibilias.ui.tools.parser.WebParserScreen
import com.imcys.bilibilias.ui.user.UserScreen
import com.imcys.bilibilias.ui.user.UserViewModel
import com.imcys.bilibilias.ui.user.folder.UserFolderRoute
import com.imcys.bilibilias.ui.user.folder.UserFolderScreen
import com.imcys.bilibilias.ui.user.list.UserListRoute
import com.imcys.bilibilias.ui.user.list.UserListScreen
import com.imcys.bilibilias.ui.user.list.UserListSource
import com.imcys.bilibilias.ui.user.navigation.UserRoute
import org.koin.androidx.compose.koinViewModel

/**
 * BILIBILAIS导航显示组件
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun BILIBILAISNavDisplay() {
    val backStack = rememberNavBackStack(HomeRoute())
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    // 监听解析事件
    LaunchedEffect(Unit) {
        analysisHandleChannel.collect {
            backStack.addWithReuse(AnalysisRoute(it.analysisText))
        }
    }

    LaunchedEffect(Unit) {
        playVoucherErrorChannel.collect {
            backStack.removeLastOrNullSafe()
            backStack.addWithReuse(PlayVoucherErrorRoute)
        }
    }

    LaunchedEffect(Unit) {
        requestFrequentHandleChannel.collect {
            backStack.addWithReuse(RequestFrequentRoute(it.url))
        }
    }

    // B3 点通知直达：缓存通知带的"启动目标" → 直接进下载管理页
    // （DownloadRoute 默认落在"正在下载"标签页，正是点缓存通知时想看的东西）
    LaunchedEffect(Unit) {
        startTargetChannel.collect { target ->
            when (target) {
                StartTarget.DOWNLOAD_LIST -> backStack.addWithReuse(DownloadRoute())
            }
        }
    }

    fun createPopTransitionSpec() = ContentTransform(
        // 返回导航：上一个页面进入 - 从放大状态恢复
        scaleIn(
            initialScale = 1.1F,
            animationSpec = tween(
                durationMillis = 400,
                easing = FastOutSlowInEasing
            )
        ),
        // 返回导航：当前页面退出 - 淡出+放大
        fadeOut(
            animationSpec = tween(
                durationMillis = 400,
                easing = FastOutSlowInEasing
            )
        )
    )

    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNullSafe() },
            sceneStrategy = listDetailStrategy,
            entryDecorators = listOf(
                // 防止屏幕旋转等导致的重组时，页面状态丢失
                rememberSaveableStateHolderNavEntryDecorator(),
                // 限定每个页面有自己的viewmodel store
                rememberViewModelStoreNavEntryDecorator()
            ),
            transitionSpec = {
                ContentTransform(
                    // 正向导航：新页面进入 - 只是淡入
                    fadeIn(
                        animationSpec = tween(
                            durationMillis = 400,
                            easing = FastOutSlowInEasing
                        )
                    ),
                    // 正向导航：原页面退出 - 放大并保持可见
                    scaleOut(
                        targetScale = 1.1F,
                        animationSpec = tween(
                            durationMillis = 400,
                            easing = FastOutSlowInEasing
                        )
                    )
                )
            },
            popTransitionSpec = {
                createPopTransitionSpec()
            },
            predictivePopTransitionSpec = {
                createPopTransitionSpec()
            },
            entryProvider = entryProvider {
                entry<HomeRoute> {
                    HomeScreen(
                        it,
                        this@SharedTransitionLayout,
                        LocalNavAnimatedContentScope.current,
                        goToLogin = {
                            backStack.addWithReuse(LoginRoute())
                        },
                        goToUserPage = { mid ->
                            backStack.addWithReuse(UserRoute(mid = mid))
                        },
                        goToAnalysis = {
                            backStack.addWithReuse(AnalysisRoute())
                        },
                        goToDownloadPage = {
                            backStack.addWithReuse(DownloadRoute())
                        },
                        goToSetting = {
                            backStack.addWithReuse(SettingRoute)
                        },
                        goToPage = { page ->
                            backStack.addWithReuse(page)
                        }
                    )
                }
                // #5（2026-10-05）：扫码登录与 Cookie 登录合并成一个「登录」页的两个 Tab
                entry<LoginRoute> {
                    LoginScreen(
                        route = it,
                        onToBack = { backStack.removeLastOrNullSafe() },
                        onBackHomePage = {
                            backStack.clear()
                            backStack.add(HomeRoute(isFormLogin = true))
                        }
                    )
                }
                entry<UserRoute> {
                    UserScreen(
                        userRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() },
                        onToSettings = {
                            backStack.addWithReuse(SettingRoute)
                        },
                        // #4（2026-10-05）：四个列表页合成一个通用列表页，
                        // 「我的」这四个入口只是传不同的来源枚举
                        onToWorkList = { mid ->
                            backStack.add(UserListRoute(source = UserListSource.WORK, mid = mid))
                        },
                        onToBangumiFollow = { mid ->
                            backStack.add(UserListRoute(source = UserListSource.BANGUMI_FOLLOW, mid = mid))
                        },
                        onToUserFolder = { mid ->
                            backStack.add(UserFolderRoute(mid = mid))
                        },
                        onToLikeVideo = { mid ->
                            backStack.add(UserListRoute(source = UserListSource.LIKE, mid = mid))
                        },
                        onToCoinVide = { mid ->
                            backStack.add(UserListRoute(source = UserListSource.COIN, mid = mid))
                        },
                        onToPlayHistory = {
                            backStack.add(UserListRoute(source = UserListSource.HISTORY))
                        }
                    )
                }
                entry<AnalysisRoute> {
                    val vm = koinViewModel<AnalysisViewModel>(key = it.toString())
                    AnalysisScreen(
                        it,
                        vm,
                        this@SharedTransitionLayout,
                        LocalNavAnimatedContentScope.current,
                        onToBack = { backStack.removeLastOrNullSafe() },
                        goToUser = { mid ->
                            backStack.add(UserRoute(mid = mid, isAnalysisUser = true))
                        },
                        onToVideoCodingInfo = {
                            backStack.addWithReuse(VideoCodingInfoRoute)
                        },
                        onToLogin = {
                            backStack.addWithReuse(LoginRoute(isFromAnalysis = true))
                        }
                    )
                }
                entry<DownloadRoute> {
                    DownloadScreen(
                        it,
                        onToBack = { backStack.removeLastOrNullSafe() })
                }
                entry<SettingRoute>(
                    metadata = ListDetailSceneStrategy.listPane(
                        detailPlaceholder = {
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    stringResource(R.string.list_detail_empty_hint),
                                )
                            }
                        }
                    )
                ) {
                    SettingScreen(
                        onToBack = { backStack.removeLastOrNullSafe() },
                        onToLayoutTypeset = { backStack.addWithReuse(LayoutTypesetRoute) },
                        onToAbout = { backStack.addWithReuse(AboutRouter) },
                        onToFeedback = { backStack.addWithReuse(FeedbackRoute) },
                        onToStorageManagement = { backStack.addWithReuse(StorageManagementRoute) },
                        onToNamingConvention = { backStack.addWithReuse(NamingConventionRoute) },
                        onToLineConfig = { backStack.addWithReuse(LineConfigRoute) },
                        onToLogin = { backStack.addWithReuse(LoginRoute()) },
                        onLogoutFinish = {
                            backStack.firstOrNull {
                                it is UserRoute && !it.isAnalysisUser
                            }?.let {
                                backStack.remove(it)
                            }
                        },
                        onToPage = { backStack.addWithReuse(it) }
                    )
                }
                entry<PlayVoucherErrorRoute> {
                    PlayVoucherErrorPage(
                        onBlack = {
                            backStack.removeLastOrNullSafe()
                            backStack.add(HomeRoute())
                        }
                    )
                }
                // #4（2026-10-05）：点赞/投币/投稿/追番/历史 五个入口共用一个通用列表页
                entry<UserListRoute> {
                    UserListScreen(
                        userListRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<UserFolderRoute> {
                    UserFolderScreen(
                        userFolderRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                                entry<VideoCodingInfoRoute> {
                    VideoCodingInfoScreen(
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<LayoutTypesetRoute>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    LayoutTypesetScreen(
                        layoutTypesetRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<AboutRouter>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    AboutScreen(
                        aboutRouter = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                                entry<FeedbackRoute>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    FeedbackScreen(
                        feedbackRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<FrameExtractorRoute> {
                    FrameExtractorScreen(
                        frameExtractorRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<StorageManagementRoute>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    StorageManagementScreen(
                        route = it,
                        onToBack = { backStack.removeLastOrNullSafe() },
                        onToDownloadList = {
                            backStack.add(DownloadRoute(1))
                        }
                    )
                }
                entry<NamingConventionRoute>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    NamingConventionScreen(
                        namingConventionRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<RequestFrequentRoute> {
                    RequestFrequentScreen(
                        requestFrequentRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<LineConfigRoute>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    LineConfigScreen(
                        lineConfigRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<WebParserRoute> {
                    WebParserScreen(
                        webParserRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
                entry<ParsePlatformRoute> {
                    ParsePlatformScreen(
                        parsePlatformRoute = it,
                        onToBack = { backStack.removeLastOrNullSafe() }
                    )
                }
            }
        )
    }

}


/**
 * 栈内复用扩展函数
 * 如果栈中已存在相同类型的路由，则比较参数：
 * - 参数相同：将其之后的所有元素移除（目标及之前的保留）
 * - 参数不同：替换该路由实例并移除其之后的所有元素
 * 否则添加新的路由实例
 */
inline fun <reified T : NavKey> NavBackStack<T>.addWithReuse(route: T) {
    val existingIndex = indexOfFirst { it::class == T::class }

    if (existingIndex != -1) {
        val existingRoute = get(existingIndex)
        // 比较路由对象的完整内容，而不只是类型
        if (existingRoute == route) {
            // 参数相同，只需移除目标之后的所有元素
            repeat(size - existingIndex - 1) { removeAt(existingIndex + 1) }
        } else {
            // 参数不同，替换该路由并移除其之后的所有元素
            set(existingIndex, route)
            repeat(size - existingIndex - 1) { removeAt(existingIndex + 1) }
        }
    } else {
        add(route)
    }
}

/**
 * 安全移除栈顶元素扩展函数
 * 只要栈中元素大于1时才允许移除，防止最后一页被移除导致异常
 */
fun <T : NavKey> NavBackStack<T>.removeLastOrNullSafe() {
    if (this.size > 1) {
        this.removeLastOrNull()
    }
}

fun <T : NavKey> myDecorator(): NavEntryDecorator<T> =
    NavEntryDecorator(onPop = { contentKey -> }) { entry ->
        entry.Content()
    }