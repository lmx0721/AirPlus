/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.airplus.event.async.TickScheduler
import net.airplus.utils.kotlin.SharedScopes
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @see List.binarySearchBy
 */
private fun List<EventHook<*>>.findIndexByPriority(item: EventHook<*>): Int {
    var low = 0
    var high = size - 1

    while (low <= high) {
        val mid = (low + high).ushr(1)
        val midVal = get(mid)

        if (item.priority < midVal.priority)
            low = mid + 1
        else if (item.priority > midVal.priority)
            high = mid - 1
        else
            return mid
    }

    return low.inv()
}

internal inline fun <T : Any> createEventMap(valueSelector: (Class<out Event>) -> T): Map<Class<out Event>, T> =
    ALL_EVENT_CLASSES.associateWithTo(IdentityHashMap(ALL_EVENT_CLASSES.size), valueSelector)

/**
 * @author MukjepScarlet
 */
object EventManager : CoroutineScope by CoroutineScope(SupervisorJob() + SharedScopes.exceptionHandler) {
    /**
     * All normal handlers (except of scripts) should be initialized at startup on the main thread
     */
    private val registry = createEventMap { CopyOnWriteArrayList<EventHook<in Event>>() }

    /**
     * Per-listener index for O(1) targeted dispatch ([call] with a listener),
     * avoids scanning the whole registry on hot paths like FakeLag packet replay.
     */
    private val listenerIndex =
        IdentityHashMap<Listenable, MutableList<Pair<Class<out Event>, EventHook<in Event>>>>()

    init {
        TickScheduler
    }

    fun <T : Event> unregisterEventHook(eventClass: Class<out T>, eventHook: EventHook<in T>): Boolean {
        val removed = registry[eventClass]!!.remove(eventHook)

        listenerIndex[eventHook.owner]?.let { list ->
            list.removeAll { it.second === eventHook }
            if (list.isEmpty()) {
                listenerIndex.remove(eventHook.owner)
            }
        }

        return removed
    }

    // Only called from main thread
    fun <T : Event> registerEventHook(eventClass: Class<out T>, eventHook: EventHook<T>): EventHook<T> {
        val container = registry[eventClass] ?: error("Unsupported Event type: ${eventClass.simpleName}")

        eventHook as EventHook<in Event>

        check(eventHook !in container) {
            "The EventHook of ${eventHook.owner} has already been registered"
        }

        val insertIndex = container.findIndexByPriority(eventHook).let {
            if (it < 0) it.inv() else it
        }
        container.add(insertIndex, eventHook)

        listenerIndex.getOrPut(eventHook.owner) { ArrayList(2) }.add(eventClass to eventHook)

        return eventHook
    }

    fun <T : Event> call(event: T): T {
        val eventClass = event::class.java

        val hooks = registry[eventClass]!!

        val cancellable = event as? CancellableEvent

        for (hook in hooks) {
            hook.processEvent(event)

            // Once the event is cancelled, skip all remaining hooks
            if (cancellable != null && cancellable.isCancelled) {
                break
            }
        }

        return event
    }

    fun <T : Event> call(event: T, listener: Listenable): T {
        val eventClass = event::class.java

        val hooks = registry[eventClass]!!

        // Fast path: look up only the hooks owned by this listener instead of scanning
        // the entire (possibly 70+ entries) registry list for every targeted dispatch
        val ownedHooks = listenerIndex[listener]

        if (ownedHooks != null) {
            for (hook in ownedHooks) {
                if (hook.first === eventClass) {
                    hook.second.processEvent(event)
                }
            }
        } else {
            for (hook in hooks) {
                if (hook.owner === listener) {
                    hook.processEvent(event)
                }
            }
        }

        return event
    }

}
