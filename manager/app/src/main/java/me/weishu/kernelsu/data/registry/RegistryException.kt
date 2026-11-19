package me.weishu.kernelsu.data.registry

// From tiann/KernelSU RegistryParser.kt; retained with the download URL validators.
open class RegistryException(message: String, cause: Throwable? = null) : Exception(message, cause)
class RegistryFormatException(message: String, cause: Throwable? = null) : RegistryException(message, cause)
