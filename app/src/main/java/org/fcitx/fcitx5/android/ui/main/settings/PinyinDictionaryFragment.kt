/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.data.pinyin.NetworkPinyinDictionaryUpdateScheduler
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.NaiveDustman
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.notificationManager
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.queryFileName
import splitties.dimensions.dp
import timber.log.Timber
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class PinyinDictionaryFragment : Fragment(), OnItemChangedListener<PinyinDictionary> {

    private val args by lazyRoute<SettingsRoute.PinyinDict>()

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var launcher: ActivityResultLauncher<String>

    private val dustman = NaiveDustman<Boolean>()

    private val busy: AtomicBoolean = AtomicBoolean(false)

    private val networkBusy: AtomicBoolean = AtomicBoolean(false)

    private var networkDictionaries: Map<String, PinyinDictManager.NetworkDictionary> = emptyMap()

    private var uiInitialized = false

    private val ui: BaseDynamicListUi<PinyinDictionary> by lazy {
        object : BaseDynamicListUi<PinyinDictionary>(
            requireContext(),
            Mode.Custom(),
            PinyinDictManager.listDictionaries(),
            initCheckBox = { entry ->
                if (entry is LibIMEDictionary) {
                    isChecked = entry.isEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) entry.enable() else entry.disable()
                        ui.updateItem(ui.indexItem(entry), entry)
                    }
                } else {
                    isChecked = true
                    isEnabled = false
                }
            }
        ) {
            init {
                enableUndo = false
                addTouchCallback()
                // since FAB is always shown in this fragment,
                // set shouldShowFab to true to hide it when entering multi select mode
                shouldShowFab = true
                fab.setOnClickListener {
                    showAddDictionaryMenu(it)
                }
                setViewModel(viewModel)
                removable = { e -> e !is BuiltinDictionary }
                onItemLongPress = { _, entry ->
                    networkDictionaries[entry.name]?.let { showNetworkDictionaryStatusDialog(it) }
                }
            }

            override fun updateFAB() {
                // do nothing
            }

            override fun showEntry(x: PinyinDictionary): String = x.name

            override fun showEntryDetail(x: PinyinDictionary): String? {
                val dictionary = networkDictionaries[x.name] ?: return null
                val nextUpdate = PinyinDictManager.nextNetworkDictionaryUpdateTime(dictionary)
                val label = getString(R.string.network_dict_label)
                val nextUpdateText = nextUpdate?.let {
                    if (it <= System.currentTimeMillis()) {
                        getString(R.string.update_due_now)
                    } else {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(it))
                    }
                } ?: getString(R.string.not_scheduled)
                return getString(R.string.network_dict_next_update, label, nextUpdateText)
            }
        }.also {
            uiInitialized = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        createNotificationChannel()
        registerLauncher()
        refreshNetworkDictionaryCache()
        ui.addOnItemChangedListener(this)
        resetDustman()
        syncDueNetworkDictionaries()
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        args.uri?.let { importFromUri(Uri.parse(it)) }
        super.onViewCreated(view, savedInstanceState)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getText(R.string.pinyin_dict),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            requireContext().notificationManager.createNotificationChannel(channel)
        }
    }

    private fun registerLauncher() {
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                importFromUri(uri)
        }
    }

    private fun showAddDictionaryMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(R.string.import_local_dict)
            menu.add(R.string.add_network_dict)
            setOnMenuItemClickListener {
                when (it.title) {
                    getString(R.string.import_local_dict) -> launcher.launch("*/*")
                    getString(R.string.add_network_dict) -> showNetworkDictionaryDialog()
                }
                true
            }
            show()
        }
    }

    private fun showNetworkDictionaryDialog(existing: PinyinDictManager.NetworkDictionary? = null) {
        val ctx = requireContext()
        val isEdit = existing != null
        val (urlLayout, urlEditText) = ctx.materialTextInput { editText ->
            hint = ctx.getString(R.string.dict_download_url)
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (isEdit) {
                editText.setText(existing.url)
            }
        }
        val intervalSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_item,
                NETWORK_UPDATE_INTERVAL_LABELS.map { getString(it) }
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            if (isEdit) {
                val idx = NETWORK_UPDATE_INTERVAL_DAYS.indexOf(existing.intervalDays)
                if (idx >= 0) setSelection(idx)
            }
        }
        val wifiOnlyCheckBox = MaterialCheckBox(ctx).apply {
            text = ctx.getString(R.string.update_over_wifi_only)
            isChecked = existing?.wifiOnly ?: true
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
            addView(urlLayout)
            addView(intervalSpinner)
            addView(wifiOnlyCheckBox)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isEdit) R.string.edit_network_dict else R.string.add_network_dict)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .onPositiveButtonClick {
                val url = urlEditText.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) {
                    urlLayout.error = getString(R.string.cannot_be_empty)
                    return@onPositiveButtonClick false
                }
                if (isEdit) {
                    PinyinDictManager.updateNetworkDictionaryConfig(
                        name = existing.name,
                        url = url,
                        intervalDays = NETWORK_UPDATE_INTERVAL_DAYS[intervalSpinner.selectedItemPosition],
                        wifiOnly = wifiOnlyCheckBox.isChecked
                    )
                    refreshNetworkDictionaryCache()
                    NetworkPinyinDictionaryUpdateScheduler.updateSchedule(ctx)
                    val idx = ui.entries.indexOfFirst { it.name == existing.name }
                    if (idx >= 0) ui.notifyItemChanged(idx)
                } else {
                    val fileName = runCatching {
                        PinyinDictManager.resolveNetworkDictionaryFileName(url)
                    }.getOrNull()
                    if (fileName != null) {
                        val entryName = fileName.substringBeforeLast('.')
                        if (ui.entries.any { it.name == entryName }) {
                            urlLayout.error = getString(R.string.dict_already_exists)
                            return@onPositiveButtonClick false
                        }
                    }
                    downloadAndConfirmNetworkDictionary(
                        url,
                        NETWORK_UPDATE_INTERVAL_DAYS[intervalSpinner.selectedItemPosition],
                        wifiOnlyCheckBox.isChecked
                    )
                }
                true
            }
        urlEditText.requestFocus()
    }

    private fun importFromUri(uri: Uri) {
        val ctx = requireContext()
        val cr = ctx.contentResolver
        val nm = ctx.notificationManager
        lifecycleScope.launch {
            val id = IMPORT_ID++
            val fileName = cr.queryFileName(uri) ?: return@launch
            if (PinyinDictionary.Type.fromFileName(fileName) == null) {
                ctx.importErrorDialog(R.string.invalid_dict)
                return@launch
            }
            val entryName = fileName.substringBeforeLast('.')
            if (ui.entries.any { it.name == entryName }) {
                ctx.importErrorDialog(R.string.dict_already_exists)
                return@launch
            }
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                .setContentTitle(getString(R.string.pinyin_dict))
                .setContentText("${getString(R.string.importing)} $entryName")
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build().let { nm.notify(id, it) }
            try {
                val imported = withContext(Dispatchers.IO) {
                    val inputStream = cr.openInputStream(uri)!!
                    PinyinDictManager.importFromInputStream(inputStream, fileName).getOrThrow()
                }
                ui.addItem(item = imported)
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
            nm.cancel(id)
        }
    }

    private fun downloadAndConfirmNetworkDictionary(url: String, intervalDays: Int, wifiOnly: Boolean) {
        val ctx = requireContext()
        val nm = ctx.notificationManager
        lifecycleScope.launch {
            val id = IMPORT_ID++
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                .setContentTitle(getString(R.string.pinyin_dict))
                .setContentText(getString(R.string.downloading))
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build().let { nm.notify(id, it) }
            try {
                val pending = withContext(Dispatchers.IO) {
                    PinyinDictManager.downloadNetworkDictionaryForImport(url).getOrThrow()
                }
                if (!isAdded) {
                    PinyinDictManager.discardPendingNetworkDictionary(pending)
                    return@launch
                }
                val entryName = pending.fileName.substringBeforeLast('.')
                if (ui.entries.any { it.name == entryName }) {
                    PinyinDictManager.discardPendingNetworkDictionary(pending)
                    ctx.importErrorDialog(R.string.dict_already_exists)
                    return@launch
                }
                showNetworkDictionaryConfirmDialog(pending, intervalDays, wifiOnly)
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            } finally {
                nm.cancel(id)
            }
        }
    }

    private fun showNetworkDictionaryConfirmDialog(
        pending: PinyinDictManager.PendingNetworkDictionary,
        intervalDays: Int,
        wifiOnly: Boolean
    ) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.whether_import_dict)
            .setMessage(
                "${pending.fileName}\n" +
                    ".${pending.type.ext} · ${Formatter.formatFileSize(ctx, pending.sizeBytes)}"
            )
            .setPositiveButton(R.string.import_) { _, _ ->
                importDownloadedNetworkDictionary(pending, intervalDays, wifiOnly)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                PinyinDictManager.discardPendingNetworkDictionary(pending)
            }
            .setOnCancelListener {
                PinyinDictManager.discardPendingNetworkDictionary(pending)
            }
            .show()
    }

    private fun importDownloadedNetworkDictionary(
        pending: PinyinDictManager.PendingNetworkDictionary,
        intervalDays: Int,
        wifiOnly: Boolean
    ) {
        val ctx = requireContext()
        val nm = ctx.notificationManager
        lifecycleScope.launch {
            val id = IMPORT_ID++
            val entryName = pending.fileName.substringBeforeLast('.')
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                .setContentTitle(getString(R.string.pinyin_dict))
                .setContentText("${getString(R.string.importing)} $entryName")
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build().let { nm.notify(id, it) }
            try {
                val imported = withContext(Dispatchers.IO) {
                    PinyinDictManager.importDownloadedNetworkDictionary(
                        pending = pending,
                        url = pending.url,
                        intervalDays = intervalDays,
                        wifiOnly = wifiOnly
                    ).getOrThrow()
                }
                refreshNetworkDictionaryCache()
                addOrUpdateDictionary(imported)
                NetworkPinyinDictionaryUpdateScheduler.updateSchedule(ctx)
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            } finally {
                nm.cancel(id)
            }
        }
    }

    private fun syncDueNetworkDictionaries() {
        if (!networkBusy.compareAndSet(false, true)) return
        lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    PinyinDictManager.syncDueNetworkDictionaries(
                        includeWifiOnlyDictionaries = PinyinDictManager.canUpdateWifiOnlyDictionaries()
                    ).getOrThrow()
                }
                refreshNetworkDictionaryCache()
                updated.forEach { addOrUpdateDictionary(it) }
                if (updated.isNotEmpty()) {
                    reloadDict()
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to update network pinyin dictionaries")
            } finally {
                networkBusy.set(false)
            }
        }
    }

    private fun refreshNetworkDictionaryCache() {
        networkDictionaries = PinyinDictManager.listNetworkDictionaries().associateBy { it.name }
    }

    private fun showNetworkDictionaryStatusDialog(dictionary: PinyinDictManager.NetworkDictionary) {
        val ctx = requireContext()
        val file = PinyinDictManager.getNetworkDictionaryFile(dictionary)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val statusText = buildString {
            append("${getString(R.string.network_dict_file)}: ${file?.path ?: dictionary.fileName}\n")
            append("${getString(R.string.network_dict_url)}: ${dictionary.url}\n")
            append("${getString(R.string.network_dict_size)}: ${
                if (dictionary.fileSize > 0L)
                    Formatter.formatFileSize(ctx, dictionary.fileSize)
                else getString(R.string.unknown)
            }\n")
            append("${getString(R.string.network_dict_first_loaded)}: ${
                if (dictionary.firstLoaded > 0L)
                    dateFormat.format(Date(dictionary.firstLoaded))
                else getString(R.string.unknown)
            }\n")
            append("${getString(R.string.network_dict_last_updated)}: ${
                if (dictionary.lastUpdated > 0L)
                    dateFormat.format(Date(dictionary.lastUpdated))
                else getString(R.string.unknown)
            }\n")
            val nextUpdate = PinyinDictManager.nextNetworkDictionaryUpdateTime(dictionary)
            append("${getString(R.string.network_dict_next_update_label)}: ${
                when {
                    nextUpdate == null -> getString(R.string.not_scheduled)
                    nextUpdate <= System.currentTimeMillis() -> getString(R.string.update_due_now)
                    else -> dateFormat.format(Date(nextUpdate))
                }
            }\n")
            append("${getString(R.string.network_dict_update_frequency)}: ${
                when (dictionary.intervalDays) {
                    1 -> getString(R.string.update_interval_daily)
                    7 -> getString(R.string.update_interval_weekly)
                    21 -> getString(R.string.update_interval_three_weeks)
                    30 -> getString(R.string.update_interval_monthly)
                    else -> getString(R.string.update_interval_daily)
                }
            }")
            if (dictionary.wifiOnly) {
                append(" · ${getString(R.string.update_over_wifi_only)}")
            }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.network_dict_status_title)
            .setMessage(statusText)
            .setNegativeButton(R.string.edit_network_dict) { _, _ ->
                showNetworkDictionaryDialog(dictionary)
            }
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun addOrUpdateDictionary(dictionary: LibIMEDictionary) {
        val old = ui.entries.firstOrNull { it.name == dictionary.name }
        if (old == null) {
            ui.addItem(item = dictionary)
        } else {
            ui.updateItem(ui.indexItem(old), dictionary)
        }
    }

    private fun reloadDict() {
        if (!dustman.dirty) return
        resetDustman()
        // Save the reference to NotificationManager, because reloadDict() could be called
        // right before the Fragment detached from Activity, and at the time reload completes,
        // Fragment is no longer attached to a Context, thus unable to cancel the notification.
        val nm = requireContext().notificationManager
        lifecycleScope.launch {
            if (busy.compareAndSet(false, true)) {
                val id = RELOAD_ID++
                NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                    .setContentTitle(getString(R.string.pinyin_dict))
                    .setContentText(getString(R.string.reloading))
                    .setOngoing(true)
                    .setProgress(100, 0, true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build().let { nm.notify(id, it) }
                viewModel.fcitx.runOnReady {
                    reloadPinyinDict()
                }
                nm.cancel(id)
                busy.set(false)
            }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.mapNotNull { it as? LibIMEDictionary }
            .associate { it.name to it.isEnabled })
    }

    override fun onItemAdded(idx: Int, item: PinyinDictionary) {
        item as LibIMEDictionary
        dustman.addOrUpdate(item.name, item.isEnabled)
    }

    override fun onItemRemoved(idx: Int, item: PinyinDictionary) {
        item as LibIMEDictionary
        item.file.delete()
        PinyinDictManager.removeNetworkDictionary(item.name)
        refreshNetworkDictionaryCache()
        NetworkPinyinDictionaryUpdateScheduler.updateSchedule(requireContext())
        dustman.remove(item.name)
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinDictionary>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: PinyinDictionary, new: PinyinDictionary) {
        new as LibIMEDictionary
        dustman.addOrUpdate(new.name, new.isEnabled)
    }

    override fun onStart() {
        super.onStart()
        if (uiInitialized) {
            viewModel.enableToolbarEditButton(ui.entries.isNotEmpty()) {
                ui.enterMultiSelect(requireActivity().onBackPressedDispatcher)
            }
        }
    }

    override fun onStop() {
        reloadDict()
        viewModel.disableToolbarEditButton()
        if (uiInitialized) {
            ui.exitMultiSelect()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (uiInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

    companion object {
        private var RELOAD_ID = 0
        private var IMPORT_ID = 0
        const val CHANNEL_ID = "pinyin_dict"
        private val NETWORK_UPDATE_INTERVAL_DAYS = intArrayOf(1, 7, 21, 30)
        private val NETWORK_UPDATE_INTERVAL_LABELS = intArrayOf(
            R.string.update_interval_daily,
            R.string.update_interval_weekly,
            R.string.update_interval_three_weeks,
            R.string.update_interval_monthly
        )
    }
}
