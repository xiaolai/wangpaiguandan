package cn.wpgd.app.ui

/**
 * Every foreground-on-background colour pair the screens draw, by token name. `ContrastTokenTest`
 * computes each pair's WCAG ratio from `design/tokens.json` in both themes (GDN-099): [text] needs
 * 4.5:1, [nonText] (outlines and fills that carry state) needs 3:1. A composable that draws a new
 * pair adds it here first.
 */
object ColorPairs {
    data class Pair(val foreground: String, val background: String, val use: String)

    val text: List<Pair> = listOf(
        Pair("ink", "bg", "body text on the table"),
        Pair("ink", "surface", "body text on panels and the menu"),
        Pair("ink", "surface2", "seat panels and chips"),
        Pair("ink2", "bg", "secondary text"),
        Pair("ink2", "surface", "secondary text on panels"),
        Pair("ink2", "surface2", "secondary text on seat panels, disabled controls"),
        Pair("accent", "bg", "turn line when you act"),
        Pair("accent", "surface2", "acting marker on a seat panel"),
        Pair("team.us", "surface2", "own-team label"),
        Pair("team.them", "surface2", "opponent-team label"),
        Pair("danger", "bg", "rejection reason"),
        Pair("danger", "surface", "rejection reason on a panel"),
        Pair("suit.red", "card", "hearts, diamonds, big joker"),
        Pair("suit.black", "card", "spades, clubs, small joker"),
        Pair("suit.red", "selFill", "red card, selected"),
        Pair("suit.black", "selFill", "black card, selected"),
        Pair("suit.club4", "card", "four-colour clubs"),
        Pair("suit.diamond4", "card", "four-colour diamonds"),
        Pair("suit.club4", "selFill", "four-colour clubs, selected"),
        Pair("suit.diamond4", "selFill", "four-colour diamonds, selected"),
        Pair("bg", "wild", "the 配 badge text on its wildcard fill"),
        Pair("bg", "primary", "primary (play) button label"),
        Pair("bg", "accent", "bomb flash label"),
    )

    val nonText: List<Pair> = listOf(
        Pair("sel", "bg", "selected-card outline against the table"),
        Pair("primary", "bg", "primary button fill"),
        Pair("accent", "bg", "bomb highlight outline"),
    )
}
