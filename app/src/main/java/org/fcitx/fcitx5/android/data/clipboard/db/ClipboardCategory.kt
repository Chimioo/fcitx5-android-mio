/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = ClipboardCategory.TABLE_NAME,
    indices = [Index(value = ["name"], unique = true)]
)
data class ClipboardCategory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0")
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val TABLE_NAME = "clipboard_category"
    }
}
