/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

object PinyinDictManager {

    @Serializable
    data class NetworkDictionary(
        val name: String,
        val url: String,
        val fileName: String,
        val intervalDays: Int,
        val lastUpdated: Long,
        val wifiOnly: Boolean = false,
        val firstLoaded: Long = 0L,
        val fileSize: Long = 0L
    )

    data class PendingNetworkDictionary(
        val url: String,
        val file: File,
        val fileName: String,
        val type: PinyinDictionary.Type,
        val sizeBytes: Long
    )

    private val pinyinDicDir = File(
        appContext.getExternalFilesDir(null)!!, "data/pinyin/dictionaries"
    ).also { it.mkdirs() }

    private val networkDictionaryConfigFile = File(
        appContext.getExternalFilesDir(null)!!, "data/pinyin/network_dictionaries.json"
    )

    private val builtinPinyinDictDir = File(
        DataManager.dataDir, "usr/share/fcitx5/pinyin/dictionaries"
    )

    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)

    private val scel2org5 by lazy { File(nativeDir, scel2org5Name) }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }

    fun listDictionaries(): List<PinyinDictionary> {
        val builtin = mutableListOf<PinyinDictionary>()
        builtinPinyinDictDir.listFiles()?.forEach {
            if (it.extension == PinyinDictionary.Type.LibIME.ext) {
                builtin.add(BuiltinDictionary(it))
            }
        }
        builtin.sortBy { it.name }
        val user = mutableListOf<PinyinDictionary>()
        pinyinDicDir.listFiles()?.forEach {
            PinyinDictionary.new(it)?.let { dict ->
                if (dict is LibIMEDictionary) {
                    user.add(dict)
                }
            }
        }
        user.sortBy { it.name }
        return builtin + user
    }

    fun importFromFile(file: File): Result<LibIMEDictionary> = runCatching {
        val raw =
            PinyinDictionary.new(file) ?: errorArg(R.string.exception_dict_filename, file.path)
        // convert to libime format in dictionaries dir
        // preserve original file name
        val new = raw.toLibIMEDictionary(
            File(
                pinyinDicDir,
                file.nameWithoutExtension + ".${PinyinDictionary.Type.LibIME.ext}"
            )
        )
        Timber.d("Converted $raw to $new")
        new
    }

    fun importFromInputStream(stream: InputStream, name: String): Result<LibIMEDictionary> {
        val tempFile = File(appContext.cacheDir, name)
        tempFile.outputStream().use {
            stream.copyTo(it)
        }
        val new = importFromFile(tempFile)
        tempFile.delete()
        return new
    }

    fun listNetworkDictionaries(): List<NetworkDictionary> {
        if (!networkDictionaryConfigFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<NetworkDictionary>>(networkDictionaryConfigFile.readText())
        }.getOrElse {
            Timber.w(it, "Failed to read network pinyin dictionaries")
            emptyList()
        }
    }

    fun isNetworkDictionary(name: String): Boolean =
        listNetworkDictionaries().any { it.name == name }

    fun nextNetworkDictionaryUpdateTime(dictionary: NetworkDictionary): Long? {
        if (dictionary.intervalDays <= 0 || dictionary.lastUpdated <= 0L) return null
        return dictionary.lastUpdated + dictionary.intervalDays * DAY_MS
    }

    fun canUpdateWifiOnlyDictionaries(context: Context = appContext): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return !connectivityManager.isActiveNetworkMetered
    }

    fun removeNetworkDictionary(name: String) {
        val newList = listNetworkDictionaries().filterNot { it.name == name }
        saveNetworkDictionaries(newList)
    }

    fun updateNetworkDictionaryConfig(
        name: String,
        url: String,
        intervalDays: Int,
        wifiOnly: Boolean
    ) {
        val newList = listNetworkDictionaries().map {
            if (it.name == name) it.copy(
                url = url,
                intervalDays = intervalDays,
                wifiOnly = wifiOnly
            )
            else it
        }
        saveNetworkDictionaries(newList)
    }

    fun getNetworkDictionaryFile(dictionary: NetworkDictionary): File? {
        val file = File(
            pinyinDicDir, "${dictionary.name}.${PinyinDictionary.Type.LibIME.ext}"
        )
        if (file.exists()) return file
        val disabled = File(
            pinyinDicDir, "${dictionary.name}.${PinyinDictionary.Type.LibIME.ext}.${LibIMEDictionary.DISABLE}"
        )
        return disabled.takeIf { it.exists() }
    }

    fun resolveNetworkDictionaryFileName(url: String): String {
        val request = Request.Builder().url(url).build()
        val parsedUrl = request.url

        parsedUrl.pathSegments.lastOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::supportedDictionaryFileName)
            ?.let { return it }

        listOf("filename", "file", "dict", "name").forEach { key ->
            parsedUrl.queryParameter(key)
                ?.takeIf { it.isNotBlank() }
                ?.let(::supportedDictionaryFileName)
                ?.let { return it }
        }

        errorArg(R.string.invalid_dict_url)
    }

    fun downloadNetworkDictionaryForImport(url: String): Result<PendingNetworkDictionary> =
        runCatching {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }
                val tempFile = File.createTempFile("network-pinyin-dict-", ".download", appContext.cacheDir)
                val body = response.body ?: throw IOException("Empty response body")
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                val headerFileName = response.header("Content-Disposition")
                    ?.let(::parseContentDispositionFileName)
                val urlFileName = runCatching { resolveNetworkDictionaryFileName(url) }.getOrNull()
                val type = detectDictionaryType(tempFile)
                    ?: headerFileName?.let(PinyinDictionary.Type::fromFileName)
                    ?: urlFileName?.let(PinyinDictionary.Type::fromFileName)
                    ?: run {
                        tempFile.delete()
                        errorArg(R.string.invalid_dict)
                    }
                val fileName = listOfNotNull(headerFileName, urlFileName)
                    .firstOrNull { PinyinDictionary.Type.fromFileName(it) == type }
                    ?: inferDownloadedDictionaryFileName(url, type)
                PendingNetworkDictionary(
                    url = url,
                    file = tempFile,
                    fileName = fileName,
                    type = type,
                    sizeBytes = tempFile.length()
                )
            }
        }

    fun discardPendingNetworkDictionary(pending: PendingNetworkDictionary) {
        pending.file.delete()
    }

    fun importDownloadedNetworkDictionary(
        pending: PendingNetworkDictionary,
        url: String,
        intervalDays: Int,
        wifiOnly: Boolean
    ): Result<LibIMEDictionary> = runCatching {
        val namedFile = File(appContext.cacheDir, pending.fileName)
        try {
            pending.file.copyTo(namedFile, overwrite = true)
            val imported = importFromFile(namedFile).getOrThrow()
            val existing = listNetworkDictionaries().find { it.name == imported.name }
            saveNetworkDictionary(
                NetworkDictionary(
                    name = imported.name,
                    url = url,
                    fileName = pending.fileName,
                    intervalDays = intervalDays,
                    lastUpdated = System.currentTimeMillis(),
                    wifiOnly = wifiOnly,
                    firstLoaded = existing?.firstLoaded
                        ?: System.currentTimeMillis(),
                    fileSize = existing?.fileSize ?: pending.sizeBytes
                )
            )
            imported
        } finally {
            pending.file.delete()
            namedFile.delete()
        }
    }

    fun syncDueNetworkDictionaries(
        now: Long = System.currentTimeMillis(),
        includeWifiOnlyDictionaries: Boolean = true
    ): Result<List<LibIMEDictionary>> =
        runCatching {
            val subscriptions = listNetworkDictionaries()
            if (subscriptions.isEmpty()) return@runCatching emptyList()
            val updated = mutableListOf<LibIMEDictionary>()
            var changed = false
            val newSubscriptions = subscriptions.map { subscription ->
                if (!subscription.isDue(now)) return@map subscription
                if (subscription.wifiOnly && !includeWifiOnlyDictionaries) return@map subscription
                runCatching {
                    val wasEnabled = findUserDictionary(subscription.name)?.isEnabled ?: true
                    updateNetworkDictionary(subscription).also {
                        if (!wasEnabled) it.disable()
                    }
                }.fold(
                    onSuccess = {
                        updated.add(it)
                        changed = true
                        subscription.copy(lastUpdated = now)
                    },
                    onFailure = {
                        Timber.w(it, "Failed to update network pinyin dictionary ${subscription.name}")
                        subscription
                    }
                )
            }
            if (changed) {
                saveNetworkDictionaries(newSubscriptions)
            }
            updated
        }

    fun sougouDictConv(src: String, dest: String) {
        val process = Runtime.getRuntime()
            .exec(
                arrayOf(scel2org5.absolutePath, "-o", dest, src),
                arrayOf("LD_LIBRARY_PATH=${nativeDir.absolutePath}")
            )
        process.waitFor()
        if (process.exitValue() != 0) {
            throw IOException(process.errorStream.bufferedReader().readText())
        }
    }

    @JvmStatic
    external fun pinyinDictConv(src: String, dest: String, mode: Boolean)

    const val MODE_BIN_TO_TXT = true
    const val MODE_TXT_TO_BIN = false
    private const val scel2org5Name = "libscel2org5.so"
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun NetworkDictionary.isDue(now: Long): Boolean {
        if (intervalDays <= 0) return false
        if (lastUpdated <= 0L) return true
        return now - lastUpdated >= intervalDays * DAY_MS
    }

    private fun updateNetworkDictionary(subscription: NetworkDictionary): LibIMEDictionary {
        val request = Request.Builder().url(subscription.url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            return response.body?.byteStream()?.use {
                importFromInputStream(it, subscription.fileName).getOrThrow()
            } ?: throw IOException("Empty response body")
        }
    }

    private fun findUserDictionary(name: String): LibIMEDictionary? {
        val enabled = File(pinyinDicDir, "$name.${PinyinDictionary.Type.LibIME.ext}")
        if (enabled.exists()) return LibIMEDictionary(enabled)
        val disabled = File(pinyinDicDir, "$name.${PinyinDictionary.Type.LibIME.ext}.${LibIMEDictionary.DISABLE}")
        if (disabled.exists()) return LibIMEDictionary(disabled)
        return null
    }

    private fun saveNetworkDictionary(dictionary: NetworkDictionary) {
        val newList = listNetworkDictionaries()
            .filterNot { it.name == dictionary.name }
            .plus(dictionary)
            .sortedBy { it.name }
        saveNetworkDictionaries(newList)
    }

    private fun saveNetworkDictionaries(dictionaries: List<NetworkDictionary>) {
        networkDictionaryConfigFile.parentFile?.mkdirs()
        networkDictionaryConfigFile.writeText(json.encodeToString(dictionaries))
    }

    private fun parseContentDispositionFileName(value: String): String? {
        val encoded = Regex("""filename\*=UTF-8''([^;]+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
        val plain = Regex("""filename="?([^";]+)"?""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
        return (encoded ?: plain)?.let(::supportedDictionaryFileName)
    }

    private fun detectDictionaryType(file: File): PinyinDictionary.Type? {
        val header = file.inputStream().use { input ->
            ByteArray(16).also { buffer ->
                val read = input.read(buffer)
                if (read <= 0) return null
            }
        }
        val sougouHeaders = arrayOf(
            byteArrayOf(0x40, 0x15, 0x00, 0x00, 0x44, 0x43, 0x53, 0x01, 0x01, 0x00, 0x00, 0x00),
            byteArrayOf(0x40, 0x15, 0x00, 0x00, 0x45, 0x43, 0x53, 0x01, 0x01, 0x00, 0x00, 0x00),
            byteArrayOf(0x40, 0x15, 0x00, 0x00, 0xd2.toByte(), 0x6d, 0x53, 0x01, 0x01, 0x00, 0x00, 0x00)
        )
        if (sougouHeaders.any { header.startsWithBytes(it) }) return PinyinDictionary.Type.Sougou
        if (
            header.size >= 4 &&
            header[0] == 0x13.toByte() &&
            header[1] == 0xc6.toByte() &&
            header[2] == 0x0f.toByte() &&
            header[3] == 0x00.toByte()
        ) {
            return PinyinDictionary.Type.LibIME
        }
        val sample = file.inputStream().buffered().use { input ->
            ByteArray(4096).also { buffer ->
                val read = input.read(buffer)
                if (read <= 0) return null
                if (buffer.take(read).any { it == 0.toByte() }) return null
                return@use buffer.copyOf(read)
            }
        }
        return runCatching {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sample))
                .toString()
            val hasDictionaryLine = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .take(32)
                .any { line ->
                    val parts = line.split(Regex("""\s+"""))
                    parts.size >= 2 && parts[0].all { it in 'a'..'z' || it == '\'' }
                }
            PinyinDictionary.Type.Text.takeIf { hasDictionaryLine }
        }.getOrNull()
    }

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun inferDownloadedDictionaryFileName(
        url: String,
        type: PinyinDictionary.Type
    ): String {
        val parsedUrl = Request.Builder().url(url).build().url
        val candidate = listOf("filename", "file", "dict", "name")
            .firstNotNullOfOrNull { key ->
                parsedUrl.queryParameter(key)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::sanitizeDictionaryFileName)
                    ?.takeIf { it.isNotBlank() }
            }
            ?: parsedUrl.pathSegments.lastOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(::sanitizeDictionaryFileName)
                ?.takeIf { it.isNotBlank() }
            ?: "network-dictionary"
        val baseName = candidate
            .removeSuffix(".${PinyinDictionary.Type.LibIME.ext}")
            .removeSuffix(".${PinyinDictionary.Type.Sougou.ext}")
            .removeSuffix(".${PinyinDictionary.Type.Text.ext}")
            .takeIf { it.isNotBlank() }
            ?: "network-dictionary"
        return "$baseName.${type.ext}"
    }

    private fun supportedDictionaryFileName(fileName: String): String? {
        val sanitized = sanitizeDictionaryFileName(fileName)
        return sanitized.takeIf { PinyinDictionary.Type.fromFileName(it) != null }
    }

    private fun sanitizeDictionaryFileName(fileName: String): String =
        URLDecoder.decode(fileName, Charsets.UTF_8.name())
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()

}
