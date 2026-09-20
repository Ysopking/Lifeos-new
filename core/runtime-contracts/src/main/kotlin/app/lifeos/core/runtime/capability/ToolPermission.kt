package app.lifeos.core.runtime.capability

enum class ToolPermission {
    READ_LOCAL_FILE,
    WRITE_TEMP_FILE,
    WRITE_USER_FILE,
    NETWORK_ACCESS,
    DATABASE_READ,
    DATABASE_WRITE,
    START_WORKER,
    INVOKE_TOOL,
    READ_MEMORY,
    WRITE_MEMORY,
    READ_REPOSITORY,
    MODIFY_REPOSITORY,
}
