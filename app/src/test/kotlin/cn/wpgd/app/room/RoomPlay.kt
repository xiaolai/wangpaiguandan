package cn.wpgd.app.room

import cn.wpgd.app.ScriptedHuman
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.table.TableViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A phone's table screen and a scripted person tapping it. */
class Seated(val phone: Phone, seed: Long) {
    val vm = TableViewModel(phone.owner, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    val human = ScriptedHuman(vm, { phone.table?.view }, seed)
}

/** A started room: the host at SOUTH, [joiners] at the given seats, AI in every other seat. */
fun RoomRig.startedRoom(host: Phone, joiners: Map<Seat, Phone>, watchers: List<Phone> = emptyList()): List<Seated> {
    joiners.forEach { (seat, p) -> join(p, host, seat) }
    watchers.forEach { join(it, host, seat = null) }
    for (s in Seat.entries) if (s != Seat.SOUTH && s !in joiners) host.owner.setAiSeat(s)
    joiners.values.forEach { it.owner.setReady(true) }
    until { LobbyUi.of(host.state, null)?.canStart == true }
    host.owner.startMatch()
    val all = listOf(host) + joiners.values
    until { all.all { (it.table?.view?.match?.dealNumber ?: 0) == 1 } }
    return all.mapIndexed { i, p -> Seated(p, seed * 10 + i) }
}

/** Every scripted person acts when its screen lets it; the host deals on after each deal. */
fun RoomRig.drive(people: List<Seated>, maxMs: Long = 3_000_000, done: () -> Boolean): Boolean {
    val end = scheduler.now + maxMs
    while (!done()) {
        if (scheduler.now >= end) return false
        people.forEach { it.human.act() }
        people.first().vm.continueToNextDeal()
        run(20)
    }
    return true
}
