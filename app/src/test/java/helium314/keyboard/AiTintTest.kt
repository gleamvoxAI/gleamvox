// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.latin.ai.AiGuard
import helium314.keyboard.latin.ai.AiTint
import helium314.keyboard.latin.voiceapi.SpeechEngineSetting
import helium314.keyboard.latin.voiceapi.VoiceApiProblem
import helium314.keyboard.latin.voiceapi.VoiceEngineChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage 5c: golden ✨ and microphone only when they really work through AI (docs/DESIGN.md) */
class AiTintTest {
    private val darkCircle = 0xFF292A2F.toInt()
    private val lightCircle = 0xFFFFFFFF.toInt()
    private val darkPanel = 0xFF121318.toInt()
    private val lightPanel = 0xFFEBECF0.toInt()

    @Test fun goldColorsOfTheOwner() {
        assertEquals(0xFFF6C945.toInt(), AiTint.goldOn(darkCircle))
        // #E5A800 has too little contrast on white, so the darker amber of the design note
        assertTrue(AiTint.contrast(0xFFE5A800.toInt(), lightCircle) < AiTint.MIN_CONTRAST)
        assertEquals(0xFFB98300.toInt(), AiTint.goldOn(lightCircle))
    }

    @Test fun contrastIsAtLeastThreeToOne() {
        val dark = AiTint.contrast(AiTint.goldOn(darkCircle), darkCircle)
        val light = AiTint.contrast(AiTint.goldOn(lightCircle), lightCircle)
        println("CONTRAST gold dark=$dark light=$light")
        assertTrue(dark >= AiTint.MIN_CONTRAST, "dark $dark")
        assertTrue(light >= AiTint.MIN_CONTRAST, "light $light")
        // the icon of the gold panel circle (circle color of the theme on gold)
        assertTrue(AiTint.contrast(darkCircle, AiTint.GOLD_ON_DARK) >= AiTint.MIN_CONTRAST)
        assertTrue(AiTint.contrast(lightCircle, AiTint.GOLD_ON_LIGHT) >= AiTint.MIN_CONTRAST)
        // gold status text on the voice panel: readable small text
        assertTrue(AiTint.contrast(AiTint.goldTextOn(darkPanel), darkPanel) >= 4.5)
        assertTrue(AiTint.contrast(AiTint.goldTextOn(lightPanel), lightPanel) >= 4.5)
    }

    @Test fun sparkleIsGoldOnlyWhenReady() {
        assertTrue(AiTint.sparkleGold(AiGuard.button(inBuild = true, blocked = false, internetOn = true, ready = true)))
        assertFalse(AiTint.sparkleGold(AiGuard.button(inBuild = true, blocked = false, internetOn = false, ready = true)))
        assertFalse(AiTint.sparkleGold(AiGuard.button(inBuild = true, blocked = false, internetOn = true, ready = false)))
        assertFalse(AiTint.sparkleGold(AiGuard.button(inBuild = true, blocked = true, internetOn = true, ready = true)))
        assertFalse(AiTint.sparkleGold(AiGuard.button(inBuild = false, blocked = false, internetOn = true, ready = true)))
    }

    private fun inputs(
        setting: SpeechEngineSetting = SpeechEngineSetting.OPENAI, consent: Boolean = true, hasKey: Boolean = true,
        internetOn: Boolean = true, fieldBlocked: Boolean = false, capReached: Boolean = false,
        paused: VoiceApiProblem? = null, keyRejected: Boolean = false, locked: Boolean = false, inBuild: Boolean = true,
        balanceExhausted: Boolean = false,
    ) = VoiceEngineChoice.Inputs(inBuild, setting, consent, hasKey, keyRejected, internetOn, locked, fieldBlocked, capReached,
        paused, balanceExhausted)

    private fun gold(i: VoiceEngineChoice.Inputs, sensitive: Boolean = false, voiceOn: Boolean = true, permission: Boolean = true) =
        AiTint.micGold(sensitive, voiceOn, permission, VoiceEngineChoice.decide(i).openAi)

    @Test fun microphoneIsGoldOnlyWhenTheNextTapGoesToOpenAi() {
        assertTrue(gold(inputs()))
        assertTrue(gold(inputs(setting = SpeechEngineSetting.AUTO)))
        // every other case keeps the theme color
        assertFalse(gold(inputs(setting = SpeechEngineSetting.SYSTEM)))
        assertFalse(gold(inputs(consent = false)))
        assertFalse(gold(inputs(hasKey = false)))
        assertFalse(gold(inputs(internetOn = false)))
        assertFalse(gold(inputs(capReached = true)))
        assertFalse(gold(inputs(fieldBlocked = true))) // excluded app or incognito
        assertFalse(gold(inputs(paused = VoiceApiProblem.PAUSED_BALANCE)))
        assertFalse(gold(inputs(keyRejected = true)))
        assertFalse(gold(inputs(locked = true)))
        assertFalse(gold(inputs(inBuild = false))) // release variant
        assertFalse(gold(inputs(balanceExhausted = true))) // estimate 0 or OpenAI answered 402
        assertFalse(gold(inputs(), sensitive = true))
        assertFalse(gold(inputs(), voiceOn = false))
        assertFalse(gold(inputs(), permission = false))
    }

    /** owner: only ChatGPT signed in, no API key: only ✨ is gold, the microphone stays as it is (system voice) */
    @Test fun chatGptWithoutKeyMakesOnlySparkleGold() {
        assertTrue(AiTint.sparkleGold(AiGuard.button(inBuild = true, blocked = false, internetOn = true, ready = true)))
        assertFalse(gold(inputs(setting = SpeechEngineSetting.SYSTEM, consent = false, hasKey = false)))
    }

    /** a user without any connection sees no yellow */
    @Test fun noConnectionsNoYellow() {
        assertFalse(AiTint.sparkleGold(AiGuard.button(inBuild = true, blocked = false, internetOn = false, ready = false)))
        assertFalse(gold(inputs(setting = SpeechEngineSetting.SYSTEM, consent = false, hasKey = false, internetOn = false)))
    }
}
