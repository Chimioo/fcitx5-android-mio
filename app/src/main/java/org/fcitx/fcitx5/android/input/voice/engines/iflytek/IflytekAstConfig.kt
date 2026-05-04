/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright Chimioo
 */
// Created by Chimioo under LGPL-2.1 license
package org.fcitx.fcitx5.android.input.voice.engines.iflytek

import org.fcitx.fcitx5.android.input.voice.EngineConfig

data class IflytekAstConfig(
    val appId: String,
    val accessKeyId: String,
    val accessKeySecret: String,
    val lang: Lang = Lang.AutoDialect,
    val audioEncode: AudioEncode = AudioEncode.Pcm,
    val sampleRate: Int = 16000,
    val pd: Domain? = null,
    val roleType: RoleType = RoleType.Disabled,
    val engPunc: Boolean = true,
    val engVadMdn: VadMode = VadMode.Far,
) : EngineConfig {

    enum class Lang(val param: String) {
        AutoDialect("autodialect"),
        AutoMinor("autominor"),
    }

    enum class AudioEncode(val param: String) {
        Pcm("pcm_s16le"),
        OpusWb("opus-wb"),
        Speex7("speex-7"),
        Speex10("speex-10"),
    }

    enum class Domain(val param: String) {
        Court("court"), Finance("finance"), Medical("medical"),
        Tech("tech"), Sport("sport"), Edu("edu"), Isp("isp"),
        Gov("gov"), Game("game"), Ecom("ecom"), Mil("mil"),
        Com("com"), Life("life"), Ent("ent"), Culture("culture"),
        Car("car"),
    }

    enum class RoleType(val param: Int) {
        Disabled(0),
        Blind(2),
    }

    enum class VadMode(val param: Int) {
        Far(1),
        Near(2),
    }
}
