package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RwxMapEntityCodesTest {
    @Test
    fun `every code is four ascii letters and unique ignoring case`() {
        val seen = mutableSetOf<String>()
        for (entry in RwxMapEntityCodes.entries()) {
            assertEquals(4, entry.code.length, entry.code)
            assertTrue(entry.code.all { it.isLetterOrDigit() }, entry.code)
            assertTrue(seen.add(entry.code.uppercase()), "duplicate code: ${entry.code}")
            assertTrue(entry.label.isNotBlank(), entry.code)
            assertTrue(entry.typeId.isNotBlank(), entry.code)
        }
        assertEquals(95, RwxMapEntityCodes.entries().size)
    }

    @Test
    fun `unit codes use the production source prefix`() {
        val prefixes = mapOf(
            "c_tank" to "L", "builder" to "L", "combatEngineer" to "L", "hoverTank" to "L",
            "gunBoat" to "N", "builderShip" to "N", "nautilusSubmarine" to "N",
            "mechGun" to "M", "mechEngineer" to "M", "mechHeavyMissile" to "M",
            "c_interceptor" to "A", "dropship" to "A", "aaBeamGunship" to "A",
            "c_experimentalTank" to "E", "experimentalSpider" to "E", "modularSpider" to "E",
        )
        for ((typeId, prefix) in prefixes) {
            val entry = assertNotNull(RwxMapEntityCodes.entryFor(typeId, false, 1), typeId)
            assertEquals(prefix, entry.code.take(1), typeId)
        }
    }

    @Test
    fun `extractor family keeps one semantic suffix and encodes level or mode`() {
        val codes = listOf(
            "extractorT1" to "baex",
            "extractorT2" to "bbex",
            "extractorT3" to "bcex",
            "extractorT3_reinforced" to "bdex",
            "extractorT3_overclocked" to "beex",
        )
        for ((typeId, code) in codes) {
            val entry = assertNotNull(RwxMapEntityCodes.entryFor(typeId, true, 1), typeId)
            assertEquals(code, entry.code, typeId)
            assertEquals("ex", entry.code.takeLast(2), typeId)
            assertTrue(entry.code[1] in 'a'..'e', typeId)
        }
        assertEquals("extractor_t3(shield)", RwxMapEntityCodes.entryFor("extractorT3_reinforced", true, 1)!!.label)
        assertEquals("extractor_t3(overclock)", RwxMapEntityCodes.entryFor("extractorT3_overclocked", true, 1)!!.label)
    }

    @Test
    fun `hidden conversion forms keep the base code`() {
        val forms = mapOf(
            "c_amphibiousJet" to "amphibiousJet",
            "c_amphibiousJet_transition" to "amphibiousJet",
            "c_amphibiousJet_underwater" to "amphibiousJet",
            "aaBeamGunship_afterburn" to "aaBeamGunship",
            "experimentalGunshipLanded" to "experimentalGunship",
            "mechBunkerDeployed" to "mechBunker",
            "mechFlyingTakeoff" to "mechFlyingLanded",
            "nautilusSubmarineLand" to "nautilusSubmarine",
            "nautilusSubmarineSurface" to "nautilusSubmarine",
            "modularSpider_antiair" to "modularSpider",
        )
        for ((variant, base) in forms) {
            assertEquals(base, RwxMapEntityCodes.canonicalTypeId(variant), variant)
            assertEquals(RwxMapEntityCodes.entryFor(base, false, 1)?.code,
                RwxMapEntityCodes.entryFor(variant, false, 1)?.code, variant)
        }
    }

    @Test
    fun `faction prefix and case follow the side`() {
        val entry = assertNotNull(RwxMapEntityCodes.entryFor("heavyTank", false, 1))
        assertEquals("+lhtk", RwxMapEntityCodes.cell(entry, RwxMapEntityCodes.Side.SELF))
        assertEquals("+LHTK", RwxMapEntityCodes.cell(entry, RwxMapEntityCodes.Side.ALLY))
        assertEquals("-LHTK", RwxMapEntityCodes.cell(entry, RwxMapEntityCodes.Side.ENEMY))
        assertEquals("-lhtk", RwxMapEntityCodes.cell(entry, RwxMapEntityCodes.Side.NEUTRAL))
        assertEquals("LHTK/lhtk = heavy_tank", RwxMapEntityCodes.legend(entry))
    }

    @Test
    fun `upgraded factories get their own level code`() {
        assertEquals("blf1", RwxMapEntityCodes.entryFor("landFactory", true, 1)!!.code)
        assertEquals("blf2", RwxMapEntityCodes.entryFor("landFactory", true, 2)!!.code)
        assertEquals("baf1", RwxMapEntityCodes.entryFor("airFactory", true, 1)!!.code)
        assertEquals("baf2", RwxMapEntityCodes.entryFor("airFactory", true, 2)!!.code)
        assertEquals("bsf1", RwxMapEntityCodes.entryFor("seaFactory", true, 1)!!.code)
        assertEquals("bsf2", RwxMapEntityCodes.entryFor("seaFactory", true, 2)!!.code)
        // A level above the listed variants keeps the highest known code instead of vanishing.
        assertEquals("blf2", RwxMapEntityCodes.entryFor("landFactory", true, 3)!!.code)
    }

    @Test
    fun `mod only buildings and props have no code`() {
        assertNull(RwxMapEntityCodes.entryFor("wall_v", true, 1))
        assertNull(RwxMapEntityCodes.entryFor("supplyDepot", true, 1))
        assertNull(RwxMapEntityCodes.entryFor("tree", false, 1))
        assertNull(RwxMapEntityCodes.entryFor("crystalResource", false, 1))
        assertNull(RwxMapEntityCodes.entryFor("bugMelee", false, 1))
    }

    @Test
    fun `all shipped buildings are covered except the two mod extensions`() {
        val buildings = listOf(
            "commandCenter", "landFactory", "airFactory", "seaFactory", "mechFactory",
            "mechFactoryT2", "experimentalLandFactory", "laboratory",
            "extractorT1", "extractorT2", "extractorT3", "extractorT3_reinforced",
            "extractorT3_overclocked", "fabricatorT1", "fabricatorT2", "fabricatorT3",
            "c_turret_t1", "c_turret_t2_gun", "c_turret_t3_gun", "c_turret_t1_artillery",
            "c_turret_t2_artillery", "c_turret_t2_flame", "c_turret_t1_lightning",
            "c_turret_t2_lightning", "c_antiAirTurret", "c_antiAirTurretT2",
            "c_antiAirTurretT3", "antiAirTurretFlak", "outpostT1", "outpostT2",
            "laserDefence", "repairbay", "nukeLauncherC", "antiNukeLauncherC",
        )
        for (typeId in buildings) {
            assertNotNull(RwxMapEntityCodes.entryFor(typeId, true, 1), typeId)
        }
        assertEquals(34, buildings.size)
    }
}
