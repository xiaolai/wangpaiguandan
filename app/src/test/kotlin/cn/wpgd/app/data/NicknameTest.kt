package cn.wpgd.app.data

import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.OwnerDependencies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-002, audit A35: a nickname is bounded, persisted, and can never claim a seat. */
class NicknameTest {
    @Test fun boundsAndProblems() {
        assertEquals(NicknameResult.Invalid(NicknameProblem.EMPTY), Nickname.parse("   "))
        assertEquals(NicknameResult.Invalid(NicknameProblem.TOO_LONG), Nickname.parse("一二三四五六七八九十一二三"))
        assertTrue(Nickname.parse("一二三四五六七八九十一二") is NicknameResult.Valid)
        assertTrue("12 emoji are 12 code points", Nickname.parse("😀".repeat(12)) is NicknameResult.Valid)
        assertEquals(NicknameResult.Invalid(NicknameProblem.INVISIBLE_OR_CONTROL), Nickname.parse("阿​梅"))
        assertEquals(NicknameResult.Invalid(NicknameProblem.INVISIBLE_OR_CONTROL), Nickname.parse("a\nb"))
        assertEquals("阿梅", (Nickname.parse("  阿梅 ") as NicknameResult.Valid).nickname.value)
    }

    @Test fun aNameThatReadsAsASeatOrRoleIsRefused() {
        for (n in listOf("北", "东家", "south", "North", "房主", "AI", "电脑", "下家")) {
            assertEquals(n, NicknameResult.Invalid(NicknameProblem.RESERVED), Nickname.parse(n))
        }
    }

    @Test fun invisibleCharactersCannotHideInOrMakeUpAName() {
        // Round 3 #3: U+034F is a nonspacing mark, drawn as nothing, so "房\u034F主" read as 房主.
        for (n in listOf("房\u034F主", "\u034F", "东\u3164家", "\u115F", "房\uFE0F主", "a\u17B4", "\u0301\u0301")) {
            assertEquals(n, NicknameResult.Invalid(NicknameProblem.INVISIBLE_OR_CONTROL), Nickname.parse(n))
        }
        // An emoji's style selector after a symbol is not hiding anything; accents are visible.
        assertTrue(Nickname.parse("阿梅\u2764\uFE0F") is NicknameResult.Valid)
        assertTrue(Nickname.parse("Zoe\u0301") is NicknameResult.Valid)
    }

    @Test fun anUnpairedSurrogateIsRefusedButAPairedOneIsAccepted() {
        // Audit #11: a lone surrogate is malformed UTF-16 and cannot survive UTF-8 encoding.
        for (n in listOf("\uD800", "阿\uD800梅", "a\uDC00")) {
            assertEquals(n, NicknameResult.Invalid(NicknameProblem.INVISIBLE_OR_CONTROL), Nickname.parse(n))
        }
        assertTrue(Nickname.parse("阿\uD83D\uDE00") is NicknameResult.Valid)
    }

    @Test fun persistedAcrossRestarts() {
        val store = InMemoryKeyValueStore()
        assertNull(NicknameRepository(store).current())
        NicknameRepository(store).set("老王")
        assertEquals("老王", NicknameRepository(store).current()?.value)
        NicknameRepository(store).set("南家")
        assertEquals("an invalid change keeps the old name", "老王", NicknameRepository(store).current()?.value)
    }

    @Test fun theSeatComesFromTheHostNeverFromTheName() {
        val solo = VirtualSolo().start()
        assertEquals(Seat.SOUTH, solo.table?.seat)
        assertTrue(OwnerDependencies::class.java.declaredFields.none { it.name.contains("seat", ignoreCase = true) })
    }
}
