package app.cloudipix.gallery.core.remote

import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

@Serializable
internal data class CloudFolderBackup(
    val serverId: String,
    val serverName: String,
    val accountKey: String? = null,
    val discoverAll: Boolean = false,
    val folders: List<CloudFolderBackupEntry>,
)

@Serializable
internal data class CloudFolderBackupEntry(
    val path: String,
    val name: String,
    val linked: Boolean = true,
    val includeSubfolders: Boolean = false,
)

internal data class CloudFolderRestore(val serverIds: Map<String, String>, val complete: Boolean)

internal fun RemoteServer.folderBackup() = CloudFolderBackup(
    serverId = id,
    serverName = name,
    accountKey = folderBackupAccountKey(),
    discoverAll = discoverAll,
    folders = folders.orEmpty().map { CloudFolderBackupEntry(it.path, it.name, it.linked, it.includeSubfolders) },
)

internal fun RemoteServer.folderBackupAccountKey(): String =
    "${protocol.name}\u0000$address\u0000$username".encodeUtf8().sha256().hex()

internal fun CloudFolderBackup.findServer(
    servers: List<RemoteServer>,
    allowLegacySingleMatch: Boolean,
): RemoteServer? {
    val candidates = if (accountKey == null) servers else servers.filter { it.folderBackupAccountKey() == accountKey }
    return candidates.firstOrNull { it.id == serverId }
        ?: candidates.filter { it.name.equals(serverName, ignoreCase = true) }.singleOrNull()
        ?: candidates.singleOrNull().takeIf { accountKey != null || allowLegacySingleMatch }
}
