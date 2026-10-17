package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.Classifier
import cn.wpgd.app.core.rules.Combination
import cn.wpgd.app.core.rules.Judgment
import cn.wpgd.app.core.rules.WildcardAssignment

/**
 * The reading of a public play: its stated type and declaration (R7.7, D23), judged exactly as the
 * reducer judged it against [table], the combination it had to beat (null when it led), so a stray
 * suit ignored under D16 still reads and an undeclared play with two same-type readings resolves to
 * the one that beat the table. Null if not exactly one; the caller decides whether that is a failure.
 */
fun PublicPlay.reading(classifier: Classifier, table: Combination?): Combination? {
    val cards = cards.map { checkNotNull(StandardDeck[it.id]) { "unknown card ${it.id}" } }
    val declared = declaration?.map { WildcardAssignment(it.rank, it.suit) }
    val judged = classifier.judge(cards, declared, table) as? Judgment.Readable ?: return null
    return judged.beating.filter { it.type == type }.distinct().singleOrNull()
}

/**
 * The reading of the last play of a trick, each play read against the one before it ([reading]).
 * Null when the trick is empty or any play has no single reading.
 */
fun List<PublicPlay>.topReading(classifier: Classifier): Combination? {
    var table: Combination? = null
    for (play in this) table = play.reading(classifier, table) ?: return null
    return table
}
