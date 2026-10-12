package me.weishu.kernelsu.data.repository

import me.weishu.kernelsu.data.model.RepoModule

interface ModuleRepoRepository {
    /** 最近一次成功拉取实际生效的仓库地址；null = 还没成功拉过。 */
    val lastSource: String?

    suspend fun fetchModules(): Result<List<RepoModule>>
}
