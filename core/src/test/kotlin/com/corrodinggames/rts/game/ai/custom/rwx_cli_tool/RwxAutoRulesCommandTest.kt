package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool

import java.lang.reflect.Proxy
import kotlin.test.*

/**
 * `rwx-game-auto-set|list|show|clear|reset` 的命令层测试。
 *
 * 这些命令的价值在于“参数是否原样进了服务层”和“输出是否能让人一眼看出规则是否在跑”，
 * 所以用动态代理固定服务返回，断言字段传递与文字回执。
 */
class RwxAutoRulesCommandTest {
    private class FakeService {
        var setOptions: RwxLocalGameTypes.AutoRuleOptions? = null
        var lastShow: String? = null
        var lastClear: String? = null
        var resets = 0
        var calls = 0
        var page: RwxLocalGameTypes.AutoPage = page(listOf(rule()))

        val service: RwxLocalGameService = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(RwxLocalGameService::class.java),
        ) { _, method, args ->
            val values = args ?: emptyArray()
            when (method.name) {
                "autoRules" -> { calls++; page }
                "autoSet" -> {
                    calls++
                    setOptions = values[0] as RwxLocalGameTypes.AutoRuleOptions
                    page
                }
                "autoShow" -> {
                    calls++
                    lastShow = values[0] as String
                    page
                }
                "autoClear" -> {
                    calls++
                    lastClear = values[0] as String
                    page
                }
                "autoReset" -> {
                    calls++
                    resets++
                    page
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as RwxLocalGameService
    }

    private companion object {
        fun rule(
            id: String = "retreat",
            enabled: Boolean = true,
            state: String = "接管 1 个单位",
            engaged: Int = 1,
            eligible: Int = 3,
            triggers: Int = 2,
            hp: Float? = 20f,
            radius: Float? = 300f,
            x: Float? = null,
            y: Float? = null,
            units: String = "",
            engagedIds: List<Long> = listOf(7L),
            includeUnits: Boolean = false,
        ) = RwxLocalGameTypes.AutoRuleSnapshot(
            id, "低血保命回撤", "血量低于阈值时后撤", enabled, 100, 0, true, hp, x, y, radius,
            units, emptyList(), emptyList(), emptyList(), false, false, includeUnits,
            eligible, engaged, triggers, state, engagedIds,
        )

        fun page(
            rules: List<RwxLocalGameTypes.AutoRuleSnapshot>,
            recent: List<String> = emptyList(),
        ) = RwxLocalGameTypes.AutoPage(
            100, 6000, rules, recent, "自动化规则：${rules.count { it.enabled }}/${rules.size} 开启")
    }

    @Test
    fun `assist-counter enables with one command and rejects tuning parameters`() {
        val fake = FakeService()
        val executor = RwxLocalGameCommands(fake.service)
        val command = assertNotNull(RwxCliCommands.find("rwx-game-auto-set"))
        executor.execute(command, listOf("--rule", "assist-counter"))
        assertEquals("assist-counter", fake.setOptions?.rule)
        assertNull(fake.setOptions?.enabled)
        executor.execute(command, listOf("--rule", "assist-counter", "--enabled", "false"))
        assertEquals(false, fake.setOptions?.enabled)
        for (extra in listOf(
            listOf("--hp", "40"), listOf("--units", "all-army"), listOf("--radius", "60"),
            listOf("--idle", "true"), listOf("--exclude-builders"), listOf("--include-units", "true"),
        )) assertFailsWith<IllegalArgumentException> {
            executor.execute(command, listOf("--rule", "assist-counter") + extra)
        }
    }

    @Test
    fun `auto-set forwards every option to the service`() {
        val fake = FakeService()
        fake.page = page(listOf(rule()))
        val executor = RwxLocalGameCommands(fake.service)
        val command = assertNotNull(RwxCliCommands.find("rwx-game-auto-set"))
        executor.execute(command, listOf(
            "--rule", "focus-fire",
            "--enabled", "true",
            "--units", "12,13",
            "--type", "mechMinigun,c_artillery",
            "--exclude", "99,tank",
            "--hp", "35",
            "--idle", "false",
            "--exclude-builders",
            "--x", "500", "--y", "600",
            "--radius", "250",
        ))
        val options = assertNotNull(fake.setOptions)
        assertEquals("focus-fire", options.rule)
        assertEquals(true, options.enabled)
        assertEquals("12,13", options.units)
        assertNull(options.group)
        assertEquals(listOf(99L), options.excludedIds)
        assertEquals(listOf("mechMinigun", "c_artillery"), options.includeTypes)
        assertEquals(listOf("tank"), options.excludeTypes)
        assertEquals(35f, options.hpPercent)
        assertEquals(false, options.idleOnly)
        assertEquals(true, options.excludeBuilders)
        assertEquals(500f, options.x)
        assertEquals(600f, options.y)
        assertEquals(250f, options.radius)
    }

    @Test
    fun `auto-set accepts a group by name or number`() {
        val fake = FakeService()
        fake.page = page(listOf(rule()))
        val executor = RwxLocalGameCommands(fake.service)
        val command = assertNotNull(RwxCliCommands.find("rwx-game-auto-set"))
        executor.execute(command, listOf("--rule", "retreat", "--group", "3"))
        assertEquals("3", assertNotNull(fake.setOptions).group)
        assertNull(assertNotNull(fake.setOptions).units)

        assertFailsWith<IllegalArgumentException> {
            executor.execute(command, listOf("--rule", "retreat", "--group", "3", "--units", "1,2"))
        }
    }

    @Test
    fun `idle-alert accepts a boolean unit notification switch`() {
        val fake = FakeService()
        val executor = RwxLocalGameCommands(fake.service)
        val command = assertNotNull(RwxCliCommands.find("rwx-game-auto-set"))
        executor.execute(command, listOf("--rule", "idle-alert"))
        assertNull(assertNotNull(fake.setOptions).includeUnits)
        for (value in listOf(true, false)) {
            executor.execute(command, listOf("--rule", "idle-alert", "--include-units", value.toString()))
            assertEquals(value, assertNotNull(fake.setOptions).includeUnits)
        }
        for (value in listOf("yes", "1", "")) {
            assertFailsWith<IllegalArgumentException> {
                executor.execute(command, listOf("--rule", "idle-alert", "--include-units", value))
            }
        }
        fake.page = page(listOf(rule(id = "idle-alert", includeUnits = true)))
        val show = executor.execute(
            assertNotNull(RwxCliCommands.find("rwx-game-auto-show")), listOf("--rule", "idle-alert"))
        assertTrue(show.contains("includeUnits=true"), show)
    }

    @Test
    fun `auto-set rejects the removed tuning options and bad values`() {
        val fake = FakeService()
        fake.page = page(listOf(rule()))
        val executor = RwxLocalGameCommands(fake.service)
        val command = assertNotNull(RwxCliCommands.find("rwx-game-auto-set"))
        executor.execute(command, listOf("--rule", "dive"))
        assertNull(assertNotNull(fake.setOptions).enabled, "omitted --enabled must stay null (default enable)")

        for (args in listOf(
            emptyList<String>(),
            listOf("--rule"),
            listOf("--rule", "dive", "--enabled", "yes"),
            listOf("--rule", "dive", "--x", "10"),
            listOf("--rule", "dive", "--hp", "0"),
            listOf("--rule", "dive", "--unknown", "1"),
            listOf("--rule", "dive", "extra"),
            listOf("--rule", "dive", "--priority", "88"),
            listOf("--rule", "dive", "--cooldown", "1.5"),
            listOf("--rule", "dive", "--resume", "false"),
        )) {
            assertFailsWith<IllegalArgumentException> { executor.execute(command, args) }
        }
    }

    @Test
    fun `auto-list renders switch, counters and recent triggers`() {
        val fake = FakeService()
        fake.page = page(
            listOf(
                rule(id = "retreat", engaged = 2, eligible = 3, triggers = 5),
                rule(id = "dive", enabled = false, state = "未启用", engaged = 0, eligible = 0,
                    triggers = 0, hp = null, radius = null),
            ),
            recent = listOf("12:00 [retreat] #7 血量 12% 后撤，远离 #42"),
        )
        val executor = RwxLocalGameCommands(fake.service)
        val text = executor.execute(assertNotNull(RwxCliCommands.find("rwx-game-auto-list")), emptyList())
        assertTrue(text.contains("自动化规则：1/2 开启"))
        assertTrue(text.contains("[开] retreat"))
        assertTrue(text.contains("接管 2"))
        assertTrue(text.contains("触发 5"))
        assertTrue(text.contains("hp=20%"))
        assertTrue(text.contains("radius=300"))
        assertTrue(text.contains("[关] dive"))
        assertTrue(text.contains("最近触发"), "recent trigger log must be visible")
        assertTrue(text.contains("#42"))
    }

    @Test
    fun `auto-show clear and reset route to the right service calls`() {
        val fake = FakeService()
        fake.page = page(listOf(rule(engagedIds = listOf(5L, 6L))))
        val executor = RwxLocalGameCommands(fake.service)
        val show = executor.execute(
            assertNotNull(RwxCliCommands.find("rwx-game-auto-show")), listOf("--rule", "retreat"))
        assertEquals("retreat", fake.lastShow)
        assertTrue(show.contains("接管单位: 5,6"))
        assertTrue(show.contains("优先级: 100"))

        executor.execute(assertNotNull(RwxCliCommands.find("rwx-game-auto-clear")), listOf("--rule", "dive"))
        assertEquals("dive", fake.lastClear)
        assertFailsWith<IllegalArgumentException> {
            executor.execute(assertNotNull(RwxCliCommands.find("rwx-game-auto-clear")), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            executor.execute(
                assertNotNull(RwxCliCommands.find("rwx-game-auto-clear")),
                listOf("--rule", "dive", "--all"))
        }

        executor.execute(assertNotNull(RwxCliCommands.find("rwx-game-auto-reset")), emptyList())
        assertEquals(1, fake.resets)
        assertFailsWith<IllegalArgumentException> {
            executor.execute(assertNotNull(RwxCliCommands.find("rwx-game-auto-reset")), listOf("--rule", "dive"))
        }
    }

    @Test
    fun `the five automation commands are registered and the compat alias is gone`() {
        for (name in listOf(
            "rwx-game-auto-set", "rwx-game-auto-list",
            "rwx-game-auto-show", "rwx-game-auto-clear",
            "rwx-game-auto-reset",
        )) assertNotNull(RwxCliCommands.find(name), name)
        assertNull(RwxCliCommands.find("rwx-game-auto-combat"), "compat alias must be removed")
        assertNull(RwxCliCommands.find("rwx-game-auto-artillery"))
    }
}
