package app.cloudipix.gallery.core.remote

import android.net.Uri
import android.util.Xml
import app.cloudipix.gallery.core.media.readUpTo
import app.cloudipix.gallery.core.model.hasHiddenFolderSegment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.buffer
import okio.sink
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPFileFilters
import org.apache.commons.net.ftp.FTPReply
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.util.UUID
import java.util.Base64
import java.io.OutputStream
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

internal class RemoteRequestException(val failure: RemoteFailure) : IOException()

internal data class UploadVersion(val etag: String?, val modified: Long)

/**
 * 폴더 변경 목록 요청의 결과입니다. [listing]이 null이면 일반 목록으로 다시 읽습니다.
 * [rejected]는 서버가 변경 목록 요청 자체를 받지 않는다고 응답했다는 뜻입니다.
 */
internal class DavChanges(val listing: DavListing?, val rejected: Boolean = false)
internal data class UploadedFile(val hash: String, val version: UploadVersion?)

private class SyncUploadBody(val size: Long, val mime: String, val write: (OutputStream) -> String) : RequestBody() {
    lateinit var hash: String
        private set

    override fun contentType() = mime.toMediaTypeOrNull()
    override fun contentLength() = size
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) { hash = write(sink.outputStream()) }

    fun verify(response: Response) {
        val digest = response.header("Repr-Digest") ?: return
        if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) return
        val url = response.request.url
        if (response.request.method != "HEAD" && response.code != 204 &&
            response.header("Content-Location")?.let(url::resolve) != url) return
        val sha256 = digest.split(',').firstOrNull { it.substringBefore('=').trim() == "sha-256" } ?: return
        val value = sha256.substringAfter('=').substringBefore(';').trim().removeSurrounding(":")
        if (value.decodeBase64()?.hex() != hash) throw IOException("서버가 확인한 파일 내용이 원본과 다릅니다.")
    }
}

private fun Response.uploadVersion(): UploadVersion? {
    val tag = header("ETag")?.takeIf { it.length >= 2 && it.first() == '"' && it.last() == '"' }
    val modified = headers.getDate("Last-Modified")?.time ?: 0L
    return if (tag != null || modified > 0) UploadVersion(tag, modified) else null
}

@Singleton
class RemoteTransport @Inject constructor() {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(Duration.ofSeconds(8))
        .readTimeout(Duration.ofSeconds(15))
        .callTimeout(Duration.ofSeconds(45))
        .build()
    private val fileHttp = http.newBuilder().callTimeout(Duration.ZERO).build()

    internal fun normalizedAddress(protocol: RemoteProtocol, address: String): String {
        val text = address.trim()
        val ftp = protocol == RemoteProtocol.Ftp
        val url = if (ftp && text.startsWith("ftp://", ignoreCase = true)) {
            ("http://" + text.substringAfter("://")).toHttpUrlOrNull()
        } else if (!ftp) text.toHttpUrlOrNull() else null
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || url.pathSegments.any { !validSegment(it) }
        ) throw RemoteRequestException(RemoteFailure.Address)
        if (ftp) {
            // HTTP 파서의 기본 포트 대신 FTP 기본 포트를 복원한다.
            val authority = text.substringAfter("://").substringBefore('/')
            return "ftp://" + authority + url.encodedPath.trimEnd('/') + "/"
        }
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString()
    }

    internal fun root(server: RemoteServer): String =
        if (server.protocol == RemoteProtocol.WebDav) server.address
        else "/" + ftpAddress(server).pathSegments.joinToString("/").trim('/')

    internal fun isFolderUnderRoot(server: RemoteServer, path: String): Boolean {
        val root = root(server)
        if (path != root && !path.startsWith(root.trimEnd('/') + "/")) return false
        if (server.protocol == RemoteProtocol.Ftp) return path.split('/').all(::validSegment)
        val url = sameOrigin(server, path) ?: return false
        return path == url.toString() && url.query == null && url.fragment == null &&
            url.encodedPath.endsWith('/') && url.pathSegments.all(::validSegment)
    }

    internal fun isVisibleFolder(server: RemoteServer, path: String): Boolean {
        val root = root(server)
        if (path == root) return true
        val prefix = root.trimEnd('/') + "/"
        if (!path.startsWith(prefix)) return false
        val relative = path.removePrefix(prefix)
        val decoded = if (server.protocol == RemoteProtocol.WebDav) Uri.decode(relative) else relative
        return !decoded.hasHiddenFolderSegment()
    }

    internal fun restoreFolderPath(server: RemoteServer, path: String, legacy: Boolean): String? {
        if (!legacy) return path.takeIf { isFolderUnderRoot(server, it) }
        if (!path.startsWith('/')) return null
        val segments = path.removePrefix("/").removeSuffix("/").split('/').filter(String::isNotEmpty)
        if (segments.any { !validSegment(it) }) return null
        val restored = when (server.protocol) {
            RemoteProtocol.Ftp -> "/" + segments.joinToString("/")
            RemoteProtocol.WebDav -> {
                val url = server.address.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/") ?: return null
                segments.forEach(url::addPathSegment)
                if (segments.isNotEmpty()) url.addPathSegment("")
                url.build().toString()
            }
        }
        return restored.takeIf { isFolderUnderRoot(server, it) }
    }

    internal suspend fun list(server: RemoteServer, path: String, requireComplete: Boolean = false): List<RemoteEntry> =
        listDirectory(server, path, requireComplete).entries

    internal fun listDirectory(server: RemoteServer, path: String, requireComplete: Boolean): DirectoryListing = when (server.protocol) {
        RemoteProtocol.WebDav -> listDav(server, path, requireComplete).let {
            DirectoryListing(it.entries, complete = it.complete, etag = it.folderEtag)
        }
        RemoteProtocol.Ftp -> withFtp(server) { listFtp(it, path, requireComplete) }
    }

    /** 폴더 자신의 ETag만 읽습니다. 하위 변경이 ETag에 전파되는 서버가 아니면 null입니다. */
    internal fun folderEtag(server: RemoteServer, path: String): String? =
        if (server.protocol != RemoteProtocol.WebDav) null
        else listDav(server, path, requireComplete = true, depth = "0").folderEtag

    internal suspend fun listBeforeDeletion(server: RemoteServer, path: String): List<RemoteEntry> =
        if (server.protocol == RemoteProtocol.Ftp) withFtp(server) { listFtp(it, path, requireComplete = true, verifyNames = true).entries }
        else list(server, path, requireComplete = true)

    internal fun requireDirectory(server: RemoteServer, path: String) {
        if (server.protocol == RemoteProtocol.WebDav) listDav(server, path, requireComplete = true, depth = "0")
        else withFtp(server) { if (!it.changeWorkingDirectory(path)) throw IOException("FTP 폴더에 접근할 수 없습니다.") }
    }

    internal fun listFtp(ftp: FTPClient, path: String, requireComplete: Boolean, verifyNames: Boolean = false): DirectoryListing {
        if (!ftp.changeWorkingDirectory(path)) {
            val failure = if (ftp.replyCode in 400..499) RemoteFailure.Connection else RemoteFailure.Credentials
            throw RemoteRequestException(failure)
        }
        val machineListing = ftp.initiateMListParsing(path)
        val usedList = !FTPReply.isPositiveCompletion(ftp.replyCode)
        val listing = if (usedList) ftp.initiateListParsing(path) else machineListing
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) throw RemoteRequestException(RemoteFailure.Connection)
        val files = try {
            listing.getFiles(FTPFileFilters.ALL)
        } catch (error: IllegalArgumentException) {
            throw IOException("FTP 폴더 목록을 해석할 수 없습니다.", error)
        }
        var complete = !usedList || requireComplete
        val entries = files.mapNotNull { file ->
            if (file == null || file.name.isNullOrEmpty()) {
                if (requireComplete) throw IOException("FTP 폴더 목록을 해석할 수 없습니다.")
                complete = false
                return@mapNotNull null
            }
            if (file.name == "." || file.name == "..") return@mapNotNull null
            if (!usedList && file.isDirectory && file.rawListing.orEmpty().substringBefore(' ').split(';')
                    .any { it.equals("type=cdir", true) || it.equals("type=pdir", true) }
            ) return@mapNotNull null
            if (!validSegment(file.name)) {
                if (requireComplete) throw IOException("FTP 항목의 이름을 확인할 수 없습니다.")
                complete = false
                return@mapNotNull null
            }
            val unsupported = !file.isFile && !file.isDirectory
            if (unsupported && !requireComplete) {
                complete = false
                return@mapNotNull null
            }
            RemoteEntry(
                path = "${path.trimEnd('/')}/${file.name}",
                name = file.name,
                directory = file.isDirectory,
                modified = file.timestamp?.timeInMillis?.coerceAtLeast(0L) ?: 0L,
                size = file.size.coerceAtLeast(0L),
                unsupported = unsupported,
                // FTP LIST는 시간대가 생략되거나 날짜만 표시될 수 있다.
                modifiedTimeIsApproximate = usedList,
            )
        }
        if (requireComplete && (usedList || verifyNames)) {
            // 해석기가 건너뛴 행이나 서버가 누락한 항목을 삭제로 판단하지 않도록 이름을 따로 확인한다.
            val names = ftp.listNames() ?: throw IOException("FTP 파일 이름 목록을 확인할 수 없습니다.")
            val prefix = path.trimEnd('/') + "/"
            val listed = names.map { it.removePrefix(prefix).removePrefix("./") }.filterNot { it.startsWith('.') }.toSet()
            if (!FTPReply.isPositiveCompletion(ftp.replyCode) ||
                listed != entries.map { it.name }.filterNot { it.startsWith('.') }.toSet()
            ) throw IOException("FTP 폴더 목록이 일치하지 않습니다.")
        }
        return DirectoryListing(entries, complete = complete)
    }

    internal suspend fun download(
        server: RemoteServer,
        path: String,
        output: OutputStream,
        maxBytes: Long,
        preview: Boolean = false,
        allowEmpty: Boolean = false,
        etag: String? = null,
    ): Long {
        val caller = currentCoroutineContext()
        val stats = caller[RemoteReadStats]
        return when (server.protocol) {
            RemoteProtocol.WebDav -> {
                val url = sameOrigin(server, path) ?: throw RemoteRequestException(RemoteFailure.Address)
                val get = request(server, url).get()
                if (etag?.startsWith('"') == true && etag.endsWith('"')) get.header("If-Match", etag)
                execute(get.build(), fileHttp).use { response ->
                    if (response.code == 206) throw IOException("서버가 파일의 일부만 반환했습니다.")
                    val body = response.body ?: throw IOException()
                    if (preview && body.contentType()?.type != "image") throw IOException()
                    if (body.contentLength() > maxBytes) throw IOException()
                    val count = body.byteStream().copyBounded(output, maxBytes, allowEmpty, stats) { caller.ensureActive() }
                    if (body.contentLength() >= 0 && count != body.contentLength()) throw IOException("전송이 중단되었습니다.")
                    count
                }
            }
            RemoteProtocol.Ftp -> withFtp(server) { ftp ->
                val stream = ftp.retrieveFileStream(path) ?: throw IOException()
                val count = stream.use { it.copyBounded(output, maxBytes, allowEmpty, stats) { caller.ensureActive() } }
                if (!ftp.completePendingCommand()) throw IOException()
                count
            }
        }
    }

    /**
     * 파일의 [offset]부터 [length] 바이트까지 읽는다. 파일이 짧으면 있는 만큼만 돌려준다.
     * WebDAV는 범위 요청으로 그만큼만 받고, 범위 요청을 무시하는 서버나 FTP에서도 그만큼 읽은 뒤 연결을 닫는다.
     */
    internal fun readRange(server: RemoteServer, path: String, offset: Long, length: Int): ByteArray = when (server.protocol) {
        RemoteProtocol.WebDav -> {
            val url = sameOrigin(server, path) ?: throw RemoteRequestException(RemoteFailure.Address)
            val get = request(server, url).get().header("Range", "bytes=$offset-${offset + length - 1}")
            execute(get.build(), fileHttp).use { response ->
                val stream = response.body?.byteStream() ?: throw IOException()
                if (offset > 0 && response.code != 206) stream.skipFully(offset)
                stream.readUpTo(length)
            }
        }
        RemoteProtocol.Ftp -> openRead(server, path, offset).use { it.readUpTo(length) }
    }

    /**
     * 동영상 재생과 프레임 추출을 위해 [offset]부터 파일을 읽는다. 전체를 받지 않고 필요한 만큼만 읽는다.
     * 돌려준 스트림을 닫으면 연결도 함께 닫힌다.
     */
    internal fun openRead(server: RemoteServer, path: String, offset: Long): InputStream = when (server.protocol) {
        RemoteProtocol.WebDav -> {
            val url = sameOrigin(server, path) ?: throw RemoteRequestException(RemoteFailure.Address)
            val get = request(server, url).get()
            if (offset > 0) get.header("Range", "bytes=$offset-")
            val response = execute(get.build(), fileHttp)
            try {
                val stream = response.body?.byteStream() ?: throw IOException()
                if (offset > 0 && response.code != 206) stream.skipFully(offset)
                stream
            } catch (error: Throwable) {
                response.close()
                throw error
            }
        }
        RemoteProtocol.Ftp -> {
            val ftp = connectFtp(server)
            try {
                if (offset > 0) ftp.restartOffset = offset
                val stream = ftp.retrieveFileStream(path) ?: throw IOException("FTP 파일을 열 수 없습니다.")
                object : FilterInputStream(stream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            // 읽는 도중에 닫으면 전송 완료 응답을 기다리지 않고 연결을 끊는다.
                            runCatching { ftp.disconnect() }
                        }
                    }
                }
            } catch (error: Throwable) {
                runCatching { ftp.disconnect() }
                throw error
            }
        }
    }

    internal fun parent(server: RemoteServer, file: String): String =
        if (server.protocol == RemoteProtocol.WebDav) file.substringBeforeLast('/') + "/"
        else file.substringBeforeLast('/').ifEmpty { "/" }

    internal fun child(server: RemoteServer, folder: String, name: String): String {
        if (name.isBlank() || !validSegment(name) || !isFolderUnderRoot(server, folder)) throw IOException()
        return if (server.protocol == RemoteProtocol.WebDav) {
            val base = sameOrigin(server, folder) ?: throw IOException()
            base.newBuilder().addPathSegment(name).build().toString()
        } else "${folder.trimEnd('/')}/$name"
    }

    internal suspend fun createFolder(server: RemoteServer, parent: String, name: String): String {
        val path = child(server, parent, name)
        val existing = entryAt(server, parent, name)
        if (existing != null) {
            if (!existing.directory) throw IOException("같은 이름의 파일이 있습니다.")
            return existing.path
        }
        return when (server.protocol) {
            RemoteProtocol.WebDav -> {
                val url = sameOrigin(server, "$path/") ?: throw IOException()
                execute(request(server, url).method("MKCOL", null).build()).use { if (it.code != 201) throw IOException() }
                url.toString()
            }
            RemoteProtocol.Ftp -> withFtp(server) { ftp ->
                if (!ftp.makeDirectory(path)) throw IOException()
                path
            }
        }
    }

    internal fun entryAt(server: RemoteServer, folder: String, name: String): RemoteEntry? {
        val path = child(server, folder, name)
        if (server.protocol == RemoteProtocol.Ftp) return withFtp(server) { ftp ->
            val file = ftpEntryAt(ftp, path) ?: return@withFtp null
            RemoteEntry(path, name, file.isDirectory, file.timestamp?.timeInMillis ?: 0L, file.size.coerceAtLeast(0L),
                unsupported = !file.isDirectory && !file.isFile)
        }
        val url = sameOrigin(server, path) ?: throw IOException()
        val query = request(server, url).header("Depth", "0")
            .method("PROPFIND", DAV_PROPERTIES.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        var response = http.newCall(query).execute()
        if (response.code in listOf(301, 302, 307, 308) && response.header("Location")?.let(url::resolve)?.toString() == "$path/") {
            response.close()
            response = http.newCall(query.newBuilder().url("$path/").build()).execute()
        }
        return response.use {
            if (response.code == 404) return@use null
            if (response.code in setOf(401, 403)) throw RemoteRequestException(RemoteFailure.Credentials)
            if (response.code != 207) throw IOException("서버 항목을 확인할 수 없습니다.")
            try {
                parseDav(server, url, response.body?.byteStream() ?: throw IOException(), requireComplete = true,
                    report = false, single = true).entries.singleOrNull() ?: throw IOException()
            } catch (error: XmlPullParserException) { throw IOException(error) }
        }
    }

    private fun ftpEntryAt(ftp: FTPClient, path: String): FTPFile? {
        ftp.mlistFile(path)?.let { return it }
        fun missing() = ftp.replyCode == 550 && (ftp.replyString.orEmpty().contains("no such file", true) ||
            ftp.replyString.orEmpty().contains("not found", true))
        if (missing()) return null
        if (ftp.changeWorkingDirectory(path)) return FTPFile().apply { type = FTPFile.DIRECTORY_TYPE }
        // MLST 미지원 서버에는 같은 파일명으로만 LIST를 요청한다. 접근 거부를 파일 부재로 처리하지 않는다.
        if (path.any { it in "*?[]" }) throw IOException("FTP 서버에서 이 파일명을 개별 조회할 수 없습니다.")
        val rows = ftp.listFiles(path, FTPFileFilters.ALL)
        if (missing()) return null
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) throw IOException("FTP 파일을 확인할 수 없습니다.")
        if (rows.isEmpty()) return null
        return rows.singleOrNull()?.takeIf { it.name?.substringAfterLast('/') == path.substringAfterLast('/') }
            ?: throw IOException("FTP 파일 정보를 확인할 수 없습니다.")
    }

    internal suspend fun currentFile(server: RemoteServer, photo: RemotePhoto): RemoteEntry {
        val folder = parent(server, photo.path)
        if (!isFolderUnderRoot(server, folder)) throw IOException()
        val name = if (server.protocol == RemoteProtocol.WebDav) Uri.decode(photo.path.substringAfterLast('/')) else photo.path.substringAfterLast('/')
        if (server.protocol == RemoteProtocol.WebDav && photo.revision.startsWith('"') && photo.revision.endsWith('"')) {
            return RemoteEntry(photo.path, name, false, photo.modified, photo.size, photo.revision, photo.preview, photo.mimeType)
        }
        val entry = entryAt(server, folder, name)?.takeIf { it.path == photo.path && !it.directory && !it.unsupported } ?: throw IOException()
        if (entry.size != photo.size || entry.modified != photo.modified ||
            (entry.etag != null && entry.etag != photo.revision)) throw IOException("원본이 변경되었습니다.")
        return entry
    }

    internal suspend fun renameFile(server: RemoteServer, from: String, to: String, etag: String? = null) =
        moveToVacant(server, from, to, etag)

    internal suspend fun moveEntry(server: RemoteServer, entry: RemoteEntry, folder: String, name: String): RemoteEntry {
        val target = child(server, folder, name)
        val version = renameFile(server, entry.path, target, entry.etag)
        val published = if (server.protocol == RemoteProtocol.WebDav && version?.etag == null)
            publishedEntry(server, folder, name, entry.size) else null
        return entry.copy(
            path = target,
            name = name,
            modified = published?.modified ?: version?.modified?.takeIf { it > 0 } ?: entry.modified,
            etag = published?.etag ?: version?.etag,
            preview = published?.preview ?: entry.preview,
        )
    }

    private suspend fun moveToVacant(server: RemoteServer, from: String, to: String, etag: String? = null): UploadVersion? {
        if (!isFolderUnderRoot(server, parent(server, from)) || !isFolderUnderRoot(server, parent(server, to))) throw IOException()
        return when (server.protocol) {
            RemoteProtocol.WebDav -> {
                val builder = request(server, sameOrigin(server, from) ?: throw IOException())
                    .header("Destination", to).header("Overwrite", "F").method("MOVE", null)
                if (etag?.startsWith('"') == true) builder.header("If-Match", etag)
                execute(builder.build()).use {
                    if (it.code !in listOf(201, 204)) throw IOException()
                    it.uploadVersion()
                }
            }
            RemoteProtocol.Ftp -> {
                withFtp(server) { ftp ->
                    if (ftpEntryAt(ftp, to) != null) throw IOException("대상 이름이 이미 사용 중입니다.")
                    if (!ftp.rename(from, to)) throw IOException()
                    null
                }
            }
        }
    }

    internal fun deleteFile(server: RemoteServer, path: String, etag: String? = null) {
        if (!isFolderUnderRoot(server, parent(server, path))) throw IOException()
        when (server.protocol) {
            RemoteProtocol.WebDav -> {
                val builder = request(server, sameOrigin(server, path) ?: throw IOException()).delete()
                if (etag?.startsWith('"') == true) builder.header("If-Match", etag)
                execute(builder.build()).use { if (it.code !in listOf(200, 204)) throw IOException() }
            }
            RemoteProtocol.Ftp -> withFtp(server) { if (!it.deleteFile(path)) throw IOException() }
        }
    }

    internal suspend fun matches(server: RemoteServer, remotePath: String, input: File): Boolean {
        val expected = MessageDigest.getInstance("SHA-256")
        input.inputStream().use { it.copyTo(DigestOutputStream(discardBytes, expected)) }
        return expected.digest().contentEquals(digest(server, remotePath, input.length()))
    }

    internal suspend fun digest(server: RemoteServer, path: String, maxBytes: Long = Long.MAX_VALUE): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        download(server, path, DigestOutputStream(discardBytes, hash), maxBytes, allowEmpty = true)
        return hash.digest()
    }

    internal suspend fun upload(server: RemoteServer, folder: String, name: String, input: File,
        replace: RemoteEntry? = null, original: File? = null, acceptIdentical: Boolean = false, mime: String? = null,
        verifyCopy: Boolean = replace != null || acceptIdentical,
    ): RemoteEntry {
        val target = child(server, folder, name)
        if (acceptIdentical && verifyCopy) {
            val existing = entryAt(server, folder, name)
            if (existing != null) {
                if (!existing.directory && existing.path == target && matches(server, target, input)) {
                    return existing.copy(mimeType = mime ?: existing.mimeType)
                }
                throw IOException("같은 이름의 파일이 있습니다.")
            }
        }
        val body = input.asRequestBody((mime ?: photoMime(name)).toMediaTypeOrNull())
        val version = if (server.protocol == RemoteProtocol.WebDav && replace == null && !verifyCopy) {
            val url = sameOrigin(server, target) ?: throw IOException()
            val put = request(server, url).header("If-None-Match", "*").put(body).build()
            fileHttp.newCall(put).execute().use { response ->
                if (response.code == 412) throw IOException("같은 이름의 파일이 있습니다.")
                if (response.code != 201 && response.code != 204 && response.code != 200) throw IOException("파일을 추가할 수 없습니다.")
                response.uploadVersion()
            }
        } else publishUpload(server, folder, name, body, input.takeIf { verifyCopy }, replace, original)
        if (version == null || (server.protocol == RemoteProtocol.WebDav && version.etag == null)) {
            publishedEntry(server, folder, name, input.length())?.let { return it.copy(mimeType = mime ?: it.mimeType) }
        }
        return RemoteEntry(target, name, false, version?.modified ?: 0L, input.length(),
            etag = version?.etag, mimeType = mime ?: photoMime(name))
    }

    internal suspend fun uploadForSync(server: RemoteServer, folder: String, name: String, size: Long,
        replace: RemoteEntry?, mime: String, syncBackup: String, expectedDigest: ByteArray?, syncDigest: ByteArray?,
        write: (OutputStream) -> String,
    ): UploadedFile {
        val body = SyncUploadBody(size, mime, write)
        val version = publishUpload(server, folder, name, body, null, replace, null, syncBackup, expectedDigest, syncDigest)
        val published = if (server.protocol == RemoteProtocol.WebDav && version?.etag == null)
            publishedEntry(server, folder, name, size) else null
        return UploadedFile(body.hash, published?.let { UploadVersion(it.etag, it.modified) } ?: version)
    }

    private fun publishedEntry(server: RemoteServer, folder: String, name: String, size: Long): RemoteEntry? =
        try {
            entryAt(server, folder, name)?.takeIf { !it.directory && !it.unsupported && (size == 0L || it.size == size) }
        } catch (_: IOException) {
            // 쓰기는 이미 완료됐다. 조회에 실패하면 확인되지 않은 버전은 기록하지 않는다.
            null
        }

    private suspend fun publishUpload(server: RemoteServer, folder: String, name: String, body: RequestBody, verifyFrom: File?,
        replace: RemoteEntry?, original: File?,
        syncBackup: String? = null, expectedDigest: ByteArray? = null, syncDigest: ByteArray? = null,
    ): UploadVersion? {
        val target = child(server, folder, name)
        val tempPrefix = syncBackup?.removeSuffix(".backup")?.let { "$it-" }.orEmpty()
        val temp = child(server, folder, "$tempPrefix${UUID.randomUUID()}.sgpart")
        val backup = replace?.let { child(server, folder, syncBackup ?: trashFileName(name)) }
        var published = false
        try {
            val ftpVersion = when (server.protocol) {
                RemoteProtocol.WebDav -> {
                    val url = sameOrigin(server, temp) ?: throw IOException()
                    val put = request(server, url).header("If-None-Match", "*").put(body)
                    if (body is SyncUploadBody) put.header("Want-Repr-Digest", "sha-256=10").header("Accept-Encoding", "identity")
                    if (syncDigest != null) {
                        put.header("Content-Digest", "sha-256=:" + Base64.getEncoder().encodeToString(syncDigest) + ":")
                        // Nextcloud·ownCloud는 이 값을 파일과 함께 저장해 목록의 oc:checksums로 돌려준다.
                        put.header("OC-Checksum", "SHA256:" + syncDigest.toByteString().hex())
                    }
                    val hasVersion = execute(put.build(), fileHttp).use {
                        if (it.code !in listOf(200, 201, 204)) throw IOException()
                        if (body is SyncUploadBody) body.verify(it)
                        it.uploadVersion() != null
                    }
                    if (body is SyncUploadBody && !hasVersion) {
                        val head = request(server, url).head().header("Want-Repr-Digest", "sha-256=10").header("Accept-Encoding", "identity")
                        http.newCall(head.build()).execute().use {
                            if (it.isSuccessful) body.verify(it)
                        }
                    }
                    // WebDAV 서버는 MOVE 후 경로에 새 ETag를 부여할 수 있다.
                    null
                }
                RemoteProtocol.Ftp -> withFtp(server) { ftp ->
                    val stream = ftp.storeFileStream(temp) ?: throw IOException()
                    stream.sink().buffer().use(body::writeTo)
                    if (!ftp.completePendingCommand()) throw IOException()
                    ftp.mdtmFile(temp)?.timestamp?.timeInMillis?.let { UploadVersion(null, it) }
                }
            }
            // 일반 파일 이동은 게시 뒤 원본을 지울 수 있으므로 기존 전수 비교를 유지한다.
            if (verifyFrom != null && !matches(server, temp, verifyFrom)) throw IOException("전송 결과가 원본과 다릅니다.")
            currentCoroutineContext().ensureActive()
            if (replace != null) {
                if (replace.path != target || (original == null && expectedDigest == null)) throw IOException()
                val etag = replace.etag
                if (server.protocol == RemoteProtocol.WebDav && etag?.startsWith('"') == true) {
                    // 대상 ETag를 조건으로 걸어 교체 도중 다른 사용자가 저장한 내용을 보호한다.
                    val swap = request(server, sameOrigin(server, temp) ?: throw IOException())
                        .header("Destination", target).header("Overwrite", "T")
                        .header("If", "<$target> ([$etag])").method("MOVE", null)
                    return execute(swap.build()).use {
                        if (it.code !in listOf(201, 204)) throw IOException()
                        published = true
                        it.uploadVersion()
                    }
                }
                val current = entryAt(server, folder, name)?.takeIf { it.path == target && !it.directory && !it.unsupported } ?: throw IOException()
                if (current.etag != replace.etag || current.size != replace.size || current.modified != replace.modified) {
                    throw IOException("원본이 변경되었습니다.")
                }
                renameFile(server, target, requireNotNull(backup))
            }
            val finalVersion = try {
                if (backup != null) {
                    val unchanged = if (expectedDigest != null) digest(server, backup).contentEquals(expectedDigest)
                    else matches(server, backup, requireNotNull(original))
                    if (!unchanged) throw IOException("원본이 변경되었습니다.")
                }
                moveToVacant(server, temp, target)
            } catch (error: Exception) {
                if (backup != null) withContext(NonCancellable) { renameFile(server, backup, target) }
                throw error
            }
            published = true
            if (backup != null) runCatching { deleteFile(server, backup) }
            return finalVersion ?: ftpVersion
        } finally {
            if (!published) runCatching { deleteFile(server, temp) }
        }
    }

    private fun listDav(server: RemoteServer, path: String, requireComplete: Boolean, depth: String = "1"): DavListing {
        val url = sameOrigin(server, path) ?: throw RemoteRequestException(RemoteFailure.Address)
        val query = request(server, url)
            .header("Depth", depth)
            .method("PROPFIND", DAV_PROPERTIES.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        execute(query).use { response ->
            if (response.code != 207) throw IOException()
            val body = response.body ?: throw IOException()
            return try {
                parseDav(server, url, body.byteStream(), requireComplete, report = false)
            } catch (error: XmlPullParserException) {
                throw IOException(error)
            }
        }
    }

    internal fun davChanges(server: RemoteServer, path: String, token: String?): DavChanges {
        val url = sameOrigin(server, path) ?: throw RemoteRequestException(RemoteFailure.Address)
        val escaped = android.text.TextUtils.htmlEncode(token.orEmpty())
        val report = """<d:sync-collection xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            <d:sync-token>$escaped</d:sync-token><d:sync-level>1</d:sync-level>
            $DAV_PROP</d:sync-collection>"""
        val query = request(server, url).header("Depth", "0")
            .method("REPORT", report.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        http.newCall(query).execute().use { response ->
            if (response.code == 401) throw RemoteRequestException(RemoteFailure.Credentials)
            if (response.code != 207) return DavChanges(null, rejected = response.code in REPORT_REJECTED)
            val body = response.body ?: return DavChanges(null)
            return try {
                DavChanges(parseDav(server, url, body.byteStream(), requireComplete = true, report = true))
            } catch (_: XmlPullParserException) {
                DavChanges(null)
            } catch (_: IOException) {
                // 잘린 변경 목록이나 일부 항목의 오류는 일반 목록으로 다시 확인한다.
                DavChanges(null)
            }
        }
    }

    private fun parseDav(server: RemoteServer, directory: HttpUrl, source: InputStream, requireComplete: Boolean, report: Boolean,
        single: Boolean = false): DavListing {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(source, null)
        if (parser.nextTag() != XmlPullParser.START_TAG || !parser.isDav("multistatus")) throw IOException()
        val requested = directory.pathSegments.dropLastWhile(String::isEmpty)
        val parent = if (single) requested.dropLast(1) else requested
        val entries = mutableListOf<RemoteEntry>()
        val removed = mutableListOf<String>()
        var token: String? = null
        var folderEtag: String? = null
        var collectionFound = false
        var complete = true
        parser.children {
            if (report && isDav("sync-token")) {
                token = nextText().trim().takeIf(String::isNotEmpty)
                return@children
            }
            if (!isDav("response")) return@children
            var href: String? = null
            var status: Int? = null
            val properties = DavProperties()
            children {
                when {
                    isDav("href") -> href = nextText().trim()
                    isDav("status") -> status = nextText().trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                    isDav("propstat") -> properties.readStatus(this)
                }
            }
            val child = href?.let(directory::resolve)?.let { sameOrigin(server, it.toString()) } ?: run {
                if (requireComplete) throw IOException("목록 항목의 경로를 확인할 수 없습니다.")
                complete = false
                return@children
            }
            val segments = child.pathSegments.dropLastWhile(String::isEmpty)
            if (single && (segments != requested || status?.let { it !in 200..299 } == true)) throw IOException()
            if (report) {
                if (segments.size != parent.size + 1 || segments.dropLast(1) != parent || !validSegment(segments.last())) {
                    throw IOException("완전한 폴더 변경 목록이 아닙니다.")
                }
                if (status == 404) {
                    removed += segments.last()
                    return@children
                }
                if (status?.let { it !in 200..299 } == true) throw IOException("폴더 변경 항목을 읽을 수 없습니다.")
            }
            if (segments == parent && properties.readable && (!requireComplete || properties.hasResourceType)) {
                if (!properties.directory) throw RemoteRequestException(RemoteFailure.Address)
                collectionFound = true
                // 파일 ID를 알려 주는 서버(Nextcloud·ownCloud)는 하위 항목이 바뀌면 상위 폴더의 ETag도 바꾼다.
                if (properties.fileId != null) folderEtag = properties.etag
                if (!properties.hasResourceType) complete = false
                return@children
            }
            if (segments.size == parent.size + 1 && segments.dropLast(1) == parent &&
                (!properties.readable || !properties.hasResourceType || !validSegment(segments.last()))
            ) {
                if (requireComplete) throw IOException("서버에서 폴더 목록을 완전히 읽을 수 없습니다.")
                complete = false
            }
            if (segments.size != parent.size + 1 || segments.dropLast(1) != parent ||
                !validSegment(segments.last()) || !properties.readable
            ) return@children
            val path = child.encodedPath.trimEnd('/') + if (properties.directory) "/" else ""
            val locator = child.newBuilder().encodedPath(path).query(null).fragment(null).build()
            entries += RemoteEntry(
                path = locator.toString(),
                name = segments.last(),
                directory = properties.directory,
                modified = properties.modified,
                size = properties.size,
                etag = properties.etag,
                preview = properties.fileId?.let { nextcloudPreview(server, it) },
                mimeType = properties.mimeType,
                fileId = properties.fileId,
                checksum = properties.checksum,
            )
        }
        if (report) {
            if (token == null) throw IOException("폴더 변경 토큰이 없습니다.")
        } else if (!single && !collectionFound && (requireComplete || entries.isEmpty())) throw IOException()
        return DavListing(entries.distinctBy(RemoteEntry::path), removed, token, complete && (report || collectionFound),
            folderEtag.takeIf { !report })
    }

    private fun nextcloudPreview(server: RemoteServer, fileId: Long): String? {
        val url = server.address.toHttpUrlOrNull() ?: return null
        val marker = "/remote.php/dav/files/"
        if (marker !in url.encodedPath) return null
        return url.newBuilder().encodedPath(url.encodedPath.substringBefore(marker) + "/index.php/core/preview")
            .addQueryParameter("fileId", fileId.toString())
            .addQueryParameter("x", "512")
            .addQueryParameter("y", "512")
            .addQueryParameter("a", "1")
            .addQueryParameter("forceIcon", "0")
            .build().toString()
    }

    /** 미리보기 주소의 요청 크기를 [edge]로 바꾼다. 크기를 받지 않는 주소는 그대로 둔다. */
    internal fun sizedPreview(preview: String, edge: Int): String {
        val url = preview.toHttpUrlOrNull() ?: return preview
        if (url.queryParameter("x") == null || url.queryParameter("y") == null) return preview
        return url.newBuilder()
            .setQueryParameter("x", edge.toString())
            .setQueryParameter("y", edge.toString())
            .build().toString()
    }

    private fun sameOrigin(server: RemoteServer, path: String): HttpUrl? {
        val root = server.address.toHttpUrlOrNull() ?: return null
        val url = path.toHttpUrlOrNull() ?: return null
        return url.takeIf {
            it.scheme == root.scheme && it.host == root.host && it.port == root.port &&
                it.username.isEmpty() && it.password.isEmpty()
        }
    }

    private fun request(server: RemoteServer, url: HttpUrl): Request.Builder =
        Request.Builder().url(url).apply {
            if (server.username.isNotEmpty()) {
                header("Authorization", Credentials.basic(server.username, server.password, Charsets.UTF_8))
            }
        }

    private fun execute(request: Request, client: OkHttpClient = http): Response {
        val response = client.newCall(request).execute()
        if (response.isSuccessful) return response
        response.close()
        val failure = if (response.code == 401 || response.code == 403) RemoteFailure.Credentials else RemoteFailure.Connection
        throw RemoteRequestException(failure)
    }

    private fun ftpAddress(server: RemoteServer): HttpUrl =
        ("http://" + server.address.substringAfter("://")).toHttpUrlOrNull()
            ?: throw RemoteRequestException(RemoteFailure.Address)

    private inline fun <T> withFtp(server: RemoteServer, block: (FTPClient) -> T): T {
        val ftp = connectFtp(server)
        try {
            return block(ftp)
        } finally {
            if (ftp.isConnected) runCatching { ftp.disconnect() }
        }
    }

    private fun connectFtp(server: RemoteServer): FTPClient {
        val address = ftpAddress(server)
        val authority = server.address.substringAfter("://").substringBefore('/')
        val port = if (authority.substringAfterLast(']').contains(':')) address.port else 21
        val ftp = FTPClient().apply {
            connectTimeout = 8_000
            defaultTimeout = 15_000
            setDataTimeout(Duration.ofSeconds(15))
            controlEncoding = "UTF-8"
        }
        try {
            ftp.connect(address.host, port)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) throw RemoteRequestException(RemoteFailure.Connection)
            if (!ftp.login(server.username.ifBlank { "anonymous" }, server.password)) {
                throw RemoteRequestException(RemoteFailure.Credentials)
            }
            ftp.enterLocalPassiveMode()
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) throw IOException()
            return ftp
        } catch (error: Throwable) {
            if (ftp.isConnected) runCatching { ftp.disconnect() }
            throw error
        }
    }
}

/** 범위 요청을 무시한 서버의 응답에서 앞부분을 읽고 버린다. */
private fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            if (read() < 0) throw java.io.EOFException()
            remaining--
        }
    }
}

private fun validSegment(text: String): Boolean =
    text != "." && text != ".." && text.none { it == '/' || it == '\\' || it.isISOControl() }

private val discardBytes = object : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

private inline fun InputStream.copyBounded(output: OutputStream, maxBytes: Long, allowEmpty: Boolean,
    stats: RemoteReadStats?, checkActive: () -> Unit): Long {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    try {
        while (true) {
            checkActive()
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException()
            output.write(buffer, 0, count)
        }
    } finally {
        stats?.downloadedBytes?.addAndGet(total)
    }
    if (total == 0L && !allowEmpty) throw IOException()
    return total
}

private fun XmlPullParser.isDav(tag: String): Boolean = namespace == "DAV:" && name == tag

private inline fun XmlPullParser.children(each: XmlPullParser.() -> Unit) {
    val parent = depth
    while (true) {
        when (next()) {
            XmlPullParser.START_TAG -> if (depth == parent + 1) each()
            XmlPullParser.END_TAG -> if (depth == parent) return
            XmlPullParser.END_DOCUMENT -> throw IOException()
        }
    }
}

private class DavProperties {
    var readable = false
    var hasResourceType = false
    var directory = false
    var size = 0L
    var modified = 0L
    var etag: String? = null
    var mimeType: String? = null
    var fileId: Long? = null
    var checksum: String? = null

    fun readStatus(parser: XmlPullParser) {
        var success = false
        val values = DavProperties()
        parser.children {
            when {
                isDav("status") -> success = nextText().trim().split(Regex("\\s+"))
                    .getOrNull(1)?.toIntOrNull() in 200..299
                isDav("prop") -> values.readProps(this)
            }
        }
        if (!success) return
        readable = true
        hasResourceType = hasResourceType || values.hasResourceType
        directory = directory || values.directory
        size = maxOf(size, values.size)
        modified = maxOf(modified, values.modified)
        etag = values.etag ?: etag
        mimeType = values.mimeType ?: mimeType
        fileId = values.fileId ?: fileId
        checksum = values.checksum ?: checksum
    }

    private fun readProps(parser: XmlPullParser) = parser.children {
        when {
            isDav("resourcetype") -> {
                hasResourceType = true
                children { if (isDav("collection")) directory = true }
            }
            isDav("getcontentlength") -> size = nextText().toLongOrNull()?.coerceAtLeast(0) ?: 0L
            isDav("getlastmodified") -> {
                val date = nextText()
                modified = runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                    .getOrDefault(0L)
            }
            isDav("getetag") -> etag = nextText().takeIf(String::isNotBlank)
            isDav("getcontenttype") -> mimeType = nextText().substringBefore(';').trim().lowercase()
            namespace == "http://owncloud.org/ns" && name == "fileid" -> fileId = nextText().toLongOrNull()
            namespace == "http://owncloud.org/ns" && name == "checksums" -> children {
                if (namespace == "http://owncloud.org/ns" && name == "checksum") {
                    nextText().trim().takeIf(String::isNotEmpty)?.let { value -> checksum = listOfNotNull(checksum, value).joinToString(" ") }
                }
            }
        }
    }
}

internal data class DavListing(
    val entries: List<RemoteEntry>,
    val removed: List<String>,
    val token: String?,
    val complete: Boolean,
    // 목록과 같은 응답에서 읽은 폴더 자신의 ETag. 하위 변경이 전파되는 서버에서만 채운다.
    val folderEtag: String? = null,
)

// 변경 목록 요청을 지원하지 않는 서버가 돌려주는 상태 코드. 일시적인 서버 오류(5xx)는 넣지 않는다.
private val REPORT_REJECTED = setOf(400, 403, 405, 415, 422, 501)

private const val DAV_PROP = "<d:prop><d:resourcetype/><d:getcontenttype/><d:getcontentlength/><d:getlastmodified/><d:getetag/><oc:fileid/><oc:checksums/></d:prop>"
private const val DAV_PROPERTIES = """<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">$DAV_PROP</d:propfind>"""
