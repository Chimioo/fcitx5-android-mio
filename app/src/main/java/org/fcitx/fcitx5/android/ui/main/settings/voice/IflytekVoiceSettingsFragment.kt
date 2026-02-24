/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

class IflytekVoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    private val prefs = AppPrefs.getInstance()

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val enableKey = prefs.voice.enableIflytekVoiceInput.key
        val enablePref = screen.findPreference<Preference>(enableKey) ?: return
        enablePref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            val enable = newValue as? Boolean ?: return@OnPreferenceChangeListener true
            if (!enable) return@OnPreferenceChangeListener true

            if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                return@OnPreferenceChangeListener true
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(org.fcitx.fcitx5.android.R.string.iflytek_permission_required)
                .setMessage(org.fcitx.fcitx5.android.R.string.iflytek_permission_required_message)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    prefs.voice.enableIflytekVoiceInput.setValue(false)
                }
                .setPositiveButton(org.fcitx.fcitx5.android.R.string.grant_permission) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                }
                .show()
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val granted = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
        prefs.voice.enableIflytekVoiceInput.setValue(granted)
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }
}
