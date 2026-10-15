package me.weishu.kernelsu.ui.screen.modulerepo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.screen.flash.FlashIt
import me.weishu.kernelsu.ui.util.module.fetchModuleDetail
import me.weishu.kernelsu.ui.viewmodel.ModuleRepoViewModel
import me.weishu.kernelsu.ui.viewmodel.ModuleViewModel

@Composable
fun ModuleRepoScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<ModuleRepoViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val installedVm = viewModel<ModuleViewModel>()
    val installedUiState by installedVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (uiState.modules.isEmpty()) {
            viewModel.refresh()
        }
        if (installedUiState.moduleList.isEmpty()) {
            installedVm.fetchModuleList()
        }
    }

    val actions = ModuleRepoActions(
        onBack = { navigator.pop() },
        onRefresh = viewModel::refresh,
        onSearchTextChange = viewModel::updateSearchText,
        onClearSearch = { viewModel.updateSearchText("") },
        onSearchStatusChange = viewModel::updateSearchStatus,
        onSetSortOrder = viewModel::setSortOrder,
        onSaveRepoUrl = viewModel::setRepoUrl,
        onOpenRepoDetail = { module ->
            val args = RepoModuleArg(
                moduleId = module.moduleId,
                moduleName = module.moduleName,
                authors = module.authors,
                authorsList = module.authorList.map { AuthorArg(it.name, it.link) },
                latestRelease = module.latestRelease,
                latestReleaseTime = module.latestReleaseTime,
                latestAsset = module.latestAsset?.let {
                    ReleaseAssetArg(it.name, it.downloadUrl, it.size, 0, it.sha256)
                },
                releases = emptyList()
            )
            navigator.push(Route.ModuleRepoDetail(args))
        },
    )

    ModuleRepoScreenMiuix(uiState, actions)
}

@Composable
fun ModuleRepoDetailScreen(module: RepoModuleArg) {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    var readmeHtml by remember(module.moduleId) { mutableStateOf<String?>(null) }
    var readmeLoaded by remember(module.moduleId) { mutableStateOf(false) }
    var detailReleases by remember(module.moduleId) { mutableStateOf<List<ReleaseArg>>(emptyList()) }
    // 详情页的两个外链兜底：官方站点只索引它自己的模块，自建仓库的模块在官方站点的
    // 详情页与官方模块组织下都是 404 —— 过去这里硬编码了这两个必然打不开的地址。
    // 改成先空着，有作者链接就用作者链接，详情返回后再用官网主页 / 源码地址覆盖。
    val authorLink = module.authorsList.firstOrNull()?.link.orEmpty()
    var webUrl by remember(module.moduleId) { mutableStateOf(authorLink) }
    var sourceUrl by remember(module.moduleId) { mutableStateOf(authorLink) }

    LaunchedEffect(module.moduleId) {
        if (module.moduleId.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val detail = fetchModuleDetail(module.moduleId)
                    if (detail != null) {
                        readmeHtml = detail.readmeHtml
                        // 官网主页优先于站点详情页地址（v1 的详情不再给站点 URL）。
                        when {
                            detail.homepageUrl.isNotEmpty() && detail.homepageUrl != "null" ->
                                webUrl = detail.homepageUrl

                            detail.url.isNotEmpty() && detail.url != "null" -> webUrl = detail.url
                        }
                        if (detail.sourceUrl.isNotEmpty() && detail.sourceUrl != "null") {
                            sourceUrl = detail.sourceUrl
                        }
                        detailReleases = detail.releases.map { r ->
                            ReleaseArg(
                                tagName = r.tagName,
                                name = r.name,
                                publishedAt = r.publishedAt,
                                assets = r.assets.map { a ->
                                    ReleaseAssetArg(a.name, a.downloadUrl, a.size, a.downloadCount, a.sha256)
                                },
                                descriptionHTML = r.descriptionHTML
                            )
                        }
                    } else {
                        detailReleases = emptyList()
                    }
                }.onSuccess {
                    readmeLoaded = true
                }.onFailure {
                    readmeLoaded = true
                    detailReleases = emptyList()
                }
            }
        } else {
            readmeLoaded = true
        }
    }

    // 自建仓库的模块在官方站点上没有详情页，fetchModuleDetail 会拿不到任何资产，
    // 详情页就会变成"看得见装不上"。这里用列表里带回来的 latestAsset 合成一条
    // 发布记录兜底（官方模块若已包含同一个下载地址则不重复添加）。
    LaunchedEffect(module.moduleId, readmeLoaded, detailReleases) {
        val asset = module.latestAsset ?: return@LaunchedEffect
        if (!readmeLoaded) return@LaunchedEffect
        if (detailReleases.any { rel -> rel.assets.any { it.downloadUrl == asset.downloadUrl } }) {
            return@LaunchedEffect
        }
        detailReleases = listOf(
            ReleaseArg(
                tagName = module.latestRelease.ifEmpty { "latest" },
                name = module.latestRelease,
                publishedAt = module.latestReleaseTime,
                assets = listOf(asset),
                descriptionHTML = "",
            )
        ) + detailReleases
    }

    val state = ModuleRepoDetailUiState(
        module = module,
        readmeHtml = readmeHtml,
        readmeLoaded = readmeLoaded,
        detailReleases = detailReleases,
        webUrl = webUrl,
        sourceUrl = sourceUrl,
    )
    val actions = ModuleRepoDetailActions(
        onBack = { navigator.pop() },
        onOpenWebUrl = { if (webUrl.isNotEmpty()) uriHandler.openUri(webUrl) },
        onOpenUrl = uriHandler::openUri,
        onInstallModule = { uri -> navigator.push(Route.Flash(FlashIt.FlashModules(listOf(uri)))) },
    )

    ModuleRepoDetailScreenMiuix(state, actions)
}
