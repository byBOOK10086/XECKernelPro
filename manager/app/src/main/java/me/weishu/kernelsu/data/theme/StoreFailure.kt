package me.weishu.kernelsu.data.theme

/**
 * 商店数据层的**类型化失败**。
 *
 * 数据层不产出人话文案（"没有网络"这种串既不好翻译，也会把业务层和 UI 层粘死）：
 * 这里只给可判定的类型，`StoreFailureText`（UI 层）负责把它翻成本地化文案。
 *
 * 继承 [Exception] 是为了能塞进 `Result.failure`，但业务代码一律按类型分支，
 * 不读 `message`。
 */
sealed class StoreFailure(val code: String) : Exception(code) {

    /** 设备没有可用网络（连索引都还没开始请求）。 */
    class NoNetwork : StoreFailure(CODE_NO_NETWORK)

    /** 所有镜像地址都试完了，一个都没成功；[lastCode] 是最后一次的 HTTP 状态（0 = 网络异常）。 */
    class Unreachable(val attempts: Int, val lastCode: Int) : StoreFailure(CODE_UNREACHABLE)

    /** 拿到了响应，但内容不是能用的索引（结构错、全被丢弃等）。 */
    class Parse(val detail: String) : StoreFailure(CODE_PARSE)

    /** 磁盘缓存读写失败（缓存坏了不该让商店打不开，所以这个只用于写失败告警）。 */
    class Cache(val detail: String) : StoreFailure(CODE_CACHE)

    /** 用户主动取消。 */
    class Cancelled : StoreFailure(CODE_CANCELLED)

    /** 主题包校验失败（sha256 对不上 / 所有镜像都给不出正确内容）。 */
    class Corrupted(val detail: String) : StoreFailure(CODE_CORRUPTED)

    /** 兜底：没归类的异常，[detail] 仅供日志。 */
    class Unknown(val detail: String) : StoreFailure(CODE_UNKNOWN)

    companion object {
        const val CODE_NO_NETWORK = "no-network"
        const val CODE_UNREACHABLE = "unreachable"
        const val CODE_PARSE = "parse"
        const val CODE_CACHE = "cache"
        const val CODE_CANCELLED = "cancelled"
        const val CODE_CORRUPTED = "corrupted"
        const val CODE_UNKNOWN = "unknown"
    }
}
