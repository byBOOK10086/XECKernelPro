package me.weishu.kernelsu.ui.screen.appprofile

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.util.forceStopApp
import me.weishu.kernelsu.ui.util.getSepolicy
import me.weishu.kernelsu.ui.util.launchApp
import me.weishu.kernelsu.ui.util.restartApp
import me.weishu.kernelsu.ui.util.setSepolicy
import me.weishu.kernelsu.ui.viewmodel.SuperUserViewModel
import me.weishu.kernelsu.privilege.ExecPolicyStore
import me.weishu.kernelsu.privilege.SuperkeyStore
import me.weishu.kernelsu.privilege.UtsSpoofStore
import me.weishu.kernelsu.ui.viewmodel.getTemplateInfoById

@Composable
fun AppProfileScreen(uid: Int) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: SuperUserViewModel = viewModel()
    val appGroupState = remember(uid) {
        derivedStateOf {
            viewModel.uiState.value.groupedApps.find { it.uid == uid } ?: SuperUserViewModel.getGroupedApp(uid)
        }
    }
    val appGroup = appGroupState.value
    val primaryAppInfo = appGroup?.primary
    if (primaryAppInfo == null) {
        LaunchedEffect(Unit) {
            navigator.pop()
        }
        return
    }

    val packageName = primaryAppInfo.profileKey
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    val initialProfile = remember(uid, packageName, primaryAppInfo.special) {
        Natives.getAppProfile(packageName, uid).let {
            if (primaryAppInfo.special) it.copy(allowSu = false) else it
        }.also {
            if (it.allowSu && !primaryAppInfo.special) {
                it.rules = getSepolicy(packageName)
            }
        }
    }
    var profile by rememberSaveable(uid, packageName) {
        mutableStateOf(initialProfile)
    }

    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val failToUpdateSepolicy = stringResource(R.string.failed_to_update_sepolicy).format(primaryAppInfo.label)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)

    fun showMessage(message: String) {
        scope.launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 受限授权：持久层是事实源（内核表重启即空），进页面时读回。
    val initialExecPaths = remember(uid) { ExecPolicyStore.get(context, uid) }
    var execRestricted by rememberSaveable(uid) { mutableStateOf(initialExecPaths.isNotEmpty()) }
    var execPaths by rememberSaveable(uid) { mutableStateOf(initialExecPaths) }

    // Per-app uname 身份：同样以持久层为事实源。
    val initialUts = remember(uid) { UtsSpoofStore.get(context, uid) }
    var utsRelease by rememberSaveable(uid) { mutableStateOf(initialUts.first) }
    var utsVersion by rememberSaveable(uid) { mutableStateOf(initialUts.second) }

    val state = AppProfileUiState(
        uid = uid,
        packageName = packageName,
        profile = profile,
        appGroup = appGroup,
        sharedUserId = sharedUserId,
        execRestricted = execRestricted,
        execPaths = execPaths,
        utsRelease = utsRelease,
        utsVersion = utsVersion,
    )

    val actions = AppProfileActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onLaunchApp = ::launchApp,
        onForceStopApp = ::forceStopApp,
        onRestartApp = ::restartApp,
        onViewTemplate = { templateId ->
            getTemplateInfoById(templateId)?.let { info ->
                navigator.push(Route.TemplateEditor(info, true))
            }
        },
        onManageTemplate = {
            navigator.push(Route.AppProfileTemplate)
        },
        onExecPolicyChange = { restricted, paths ->
            val ok = if (!SuperkeyStore.ensureAuthed(context)) {
                showMessage(context.getString(R.string.superkey_auth_required))
                false
            } else {
                ExecPolicyStore.set(context, uid, if (restricted) paths else emptyList())
            }
            if (!ok && restricted) {
                showMessage(context.getString(R.string.exec_policy_kernel_too_old))
            }
            execRestricted = restricted && paths.any { it.isNotBlank() }
            execPaths = paths
        },
        onUtsSpoofChange = { release, version ->
            val ok = if (!SuperkeyStore.ensureAuthed(context)) {
                showMessage(context.getString(R.string.superkey_auth_required))
                false
            } else {
                UtsSpoofStore.set(context, uid, release, version)
            }
            if (!ok && (release.isNotBlank() || version.isNotBlank())) {
                showMessage(context.getString(R.string.uts_spoof_kernel_too_old))
            }
            utsRelease = release.trim()
            utsVersion = version.trim()
        },
        onProfileChange = { updatedProfile ->
            scope.launch {
                val profileToSave = if (primaryAppInfo.special) {
                    updatedProfile.copy(allowSu = false)
                } else {
                    updatedProfile
                }
                if (profileToSave.allowSu) {
                    if (uid < 2000 && uid != 1000) {
                        showMessage(suNotAllowed)
                        return@launch
                    }
                    if (!profileToSave.rootUseDefault
                        && profileToSave.rules.isNotEmpty()
                        && !primaryAppInfo.special
                        && !setSepolicy(profileToSave.name, profileToSave.rules)
                    ) {
                        showMessage(failToUpdateSepolicy)
                        return@launch
                    }
                }
                if (!SuperkeyStore.ensureAuthed(context)) {
                    showMessage(context.getString(R.string.superkey_auth_required))
                    return@launch
                }
                if (!Natives.setAppProfile(profileToSave)) {
                    showMessage(failToUpdateAppProfile)
                } else {
                    profile = profileToSave
                    viewModel.loadAppList()
                }
            }
        },
    )

    AppProfileScreenMiuix(
        state = state,
        actions = actions,
    )
}
