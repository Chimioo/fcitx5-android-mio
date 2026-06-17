/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import timber.log.Timber
import java.io.File

/**
 * Builds a v5-schema `clbdb` from the current v6 `clbdb` for downgrade-safe
 * export. The output mirrors the upstream schema: only the `clipboard`
 * table with the seven upstream columns. Both `clipboard_category` (v6-only)
 * and the `category` column on `clipboard` (also v6-only) are dropped.
 */
object CompatDbExporter {

    private val V5_CLIPBOARD_DDL = """
        CREATE TABLE IF NOT EXISTS clipboard (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            text TEXT NOT NULL,
            pinned INTEGER NOT NULL DEFAULT 0,
            timestamp INTEGER NOT NULL DEFAULT -1,
            type TEXT NOT NULL DEFAULT 'text/plain',
            deleted INTEGER NOT NULL DEFAULT 0,
            sensitive INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    private val V6_SELECT = "SELECT id, text, pinned, timestamp, type, deleted, sensitive FROM clipboard"

    private val V5_INSERT = "INSERT INTO clipboard (id, text, pinned, timestamp, type, deleted, sensitive) VALUES (?, ?, ?, ?, ?, ?, ?)"

    /**
     * Copy the on-disk `clbdb` (v6 schema) to [dest] using v5 schema. The
     * `clipboard_category` table and the `category` column on `clipboard`
     * are both dropped. [dest] is overwritten if it exists. The source file
     * is read-only; it is never modified.
     */
    fun buildV5Compat(source: File, dest: File) {
        require(source.exists()) { "Source clbdb not found: ${source.absolutePath}" }
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        SQLiteDatabase.openDatabase(source.absolutePath, null, OPEN_READONLY).use { src ->
            SQLiteDatabase.openOrCreateDatabase(dest.absolutePath, null).use { dst ->
                dst.execSQL(V5_CLIPBOARD_DDL)
                src.rawQuery(V6_SELECT, null).use { c ->
                    while (c.moveToNext()) {
                        dst.execSQL(
                            V5_INSERT,
                            arrayOf(
                                c.getInt(0), c.getString(1), c.getInt(2), c.getLong(3),
                                c.getString(4), c.getInt(5), c.getInt(6)
                            )
                        )
                    }
                }
            }
        }
        Timber.d("Built v5 compat clbdb at ${dest.absolutePath}")
    }
}
