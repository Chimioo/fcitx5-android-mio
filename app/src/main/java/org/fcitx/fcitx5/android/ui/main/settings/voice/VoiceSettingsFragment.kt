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
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.VoiceEngineRegistry

class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    private val prefs = AppPrefs.getInstance()

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        setupPermissionHandling(screen)

        setupEngineSelector(screen)

        applyEngineVisibility(screen)

       prefs.voice.voiceEngine.registerOnChangeListener { _, _ ->
            applyEngineVisibility(screen)
        }
    }

    private fun setupPermissionHandling(screen: PreferenceScreen) {
        val enableKey = prefs.voice.isVoiceInputEnabled.key
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
                .setTitle(R.string.voice_permission_required)
                .setMessage(R.string.voice_permission_required_message)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    prefs.voice.isVoiceInputEnabled.setValue(false)
                }
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQUEST_RECORD_AUDIO
                    )
                }
                .show()
            false
        }
    }

    private fun setupEngineSelector(screen: PreferenceScreen) {
        val engineKey = prefs.voice.voiceEngine.key
        val editTextPref = screen.findPreference<Preference>(engineKey) ?: return

        // Remove the auto-generated EditTextPreference (which always shows its own input dialog)
        screen.removePreference(editTextPref)

        // Create a plain Preference that acts as a clickable engine selector
        val enginePref = Preference(requireContext()).apply {
            key = engineKey
            title = getString(R.string.voice_engine)
            isPersistent = false // ManagedPreference handles persistence
            setIconSpaceReserved(false)

            // Place right after the enable toggle
            val enablePref = screen.findPreference<Preference>(prefs.voice.isVoiceInputEnabled.key)
            order = (enablePref?.order ?: -1) + 1

            onPreferenceClickListener = Preference.OnPreferenceClickListener {
                showEngineSelectionDialog()
                true
            }
        }

        screen.addPreference(enginePref)

        updateEngineSummary(enginePref)
    }

    private fun showEngineSelectionDialog() {
        val engines = VoiceEngineRegistry.listEngines()
        if (engines.isEmpty()) return

        val currentId = prefs.voice.voiceEngine.getValue()
        val engineNames = engines.map { getString(it.displayNameRes) }.toTypedArray()
        val checkedIndex = engines.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_engine)
            .setSingleChoiceItems(engineNames, checkedIndex) { dialog, which ->
                val selected = engines[which]
                prefs.voice.voiceEngine.setValue(selected.id)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyEngineVisibility(screen: PreferenceScreen) {
        val currentEngineId = prefs.voice.voiceEngine.getValue()


        VoiceEngineRegistry.listEngines().forEach { engine ->
            engine.preferenceKeys.forEach { key ->
                screen.findPreference<Preference>(key)?.let { pref ->
                    pref.isVisible = engine.id == currentEngineId
                }
            }
        }


        val engineKey = prefs.voice.voiceEngine.key
        screen.findPreference<Preference>(engineKey)?.let { pref ->
            updateEngineSummary(pref)
        }
    }

    private fun updateEngineSummary(pref: Preference) {
        val currentId = prefs.voice.voiceEngine.getValue()
        val engine = VoiceEngineRegistry.getEngine(currentId)
        pref.summary = engine?.let { getString(it.displayNameRes) } ?: currentId
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val granted = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
        prefs.voice.isVoiceInputEnabled.setValue(granted)
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }
}
