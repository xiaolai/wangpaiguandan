package com.xiaolai.wangpaiguandan.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaolai.wangpaiguandan.core.Card
import com.xiaolai.wangpaiguandan.core.Suit
import com.xiaolai.wangpaiguandan.core.fixtureDeck

/** Bootstrap component preview; deliberately not a playable or networked Guandan match. */
@Composable
fun DesignPreview() {
    var dark by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val colors = if (dark) DesignTokens.dark else DesignTokens.light
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val hand = remember {
        val pool = fixtureDeck().toMutableList()
        listOf(Suit.SPADE to 3, Suit.DIAMOND to 3, Suit.CLUB to 4, Suit.HEART to 5,
            Suit.SPADE to 5, Suit.DIAMOND to 6, Suit.SPADE to 7, Suit.HEART to 7,
            Suit.CLUB to 7, Suit.DIAMOND to 7, Suit.SPADE to 8, Suit.HEART to 8,
            Suit.CLUB to 9, Suit.DIAMOND to 10, Suit.SPADE to 10, Suit.HEART to 11,
            Suit.CLUB to 12, Suit.SPADE to 12, Suit.DIAMOND to 12, Suit.SPADE to 13,
            Suit.HEART to 14, Suit.CLUB to 14, Suit.HEART to 2, Suit.HEART to 2,
            Suit.CLUB to 2, Suit.JOKER to 16, Suit.JOKER to 17).map { (suit, rank) ->
            pool.removeAt(pool.indexOfFirst { it.suit == suit && it.rank == rank })
        }
    }
    val scheme = if (dark) darkColorScheme(
        primary = colors.getValue("primary"), onPrimary = colors.getValue("bg"),
        background = colors.getValue("bg"), surface = colors.getValue("surface"),
        onBackground = colors.getValue("ink"), onSurface = colors.getValue("ink")
    ) else lightColorScheme(
        primary = colors.getValue("primary"), onPrimary = colors.getValue("surface"),
        background = colors.getValue("bg"), surface = colors.getValue("surface"),
        onBackground = colors.getValue("ink"), onSurface = colors.getValue("ink")
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = colors.getValue("bg")) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("掼蛋", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 40.sp)
                        Text("宣纸 · 大字", fontSize = 15.sp, color = colors.getValue("ink2"))
                    }
                    Text("深色", fontSize = 15.sp)
                    Switch(checked = dark, onCheckedChange = { dark = it },
                        modifier = Modifier.semantics { contentDescription = "深色主题" })
                }
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(12.dp), color = colors.getValue("surface2")) {
                    Text("设计预览 · 点击选择手牌", Modifier.padding(12.dp), fontSize = 17.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text("打 2 · ♥\uFE0E2 逢人配 · 已选 ${selectedIds.size} 张", fontSize = 15.sp,
                    color = colors.getValue("accent"))
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(64.dp), modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hand, key = { it.id }) { card ->
                        CardTile(card, card.id in selectedIds, colors) {
                            selectedIds = if (card.id in selectedIds) selectedIds - card.id else selectedIds + card.id
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { selectedIds = emptySet() }, enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清空选择", fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun CardTile(card: Card, isSelected: Boolean,
    colors: Map<String, androidx.compose.ui.graphics.Color>, onToggle: () -> Unit) {
    val wildcard = card.isWildcard(2)
    val shape = RoundedCornerShape(6.dp)
    val face = colors.getValue(if (card.suit.isRed || card.rank == 17) "suit.red" else "suit.black")
    Box(Modifier.heightIn(min = 96.dp).background(colors.getValue(if (isSelected) "selFill" else "card"), shape)
        .border(if (isSelected) 2.dp else 1.dp, colors.getValue(if (isSelected) "accent" else "line"), shape)
        .clickable(role = Role.Checkbox, onClick = onToggle)
        .semantics {
            selected = isSelected
            contentDescription = "${card.suit.glyph}${card.label}" + if (wildcard) "，逢人配" else ""
        }.padding(8.dp)) {
        Column {
            Text(card.label, color = face, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(card.suit.glyph + "\uFE0E", color = face, fontSize = 20.sp)
        }
        if (wildcard) Text("配", color = colors.getValue("accent"), fontWeight = FontWeight.Bold,
            fontSize = 15.sp, modifier = Modifier.align(Alignment.BottomEnd))
    }
}
