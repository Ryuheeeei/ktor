/*
 * Copyright 2014-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.selector

import kotlinx.atomicfu.*
import java.util.concurrent.atomic.*

private typealias Core<E> = LockFreeMPSCQueueCore<E>

/**
 * Lock-free Multiply-Producer Single-Consumer Queue.
 * *Note: This queue is NOT linearizable. It provides only quiescent consistency for its operations.*
 *
 * In particular, the following execution is permitted for this queue, but is not permitted for a linearizable queue:
 *
 * ```
 * Thread 1: addLast(1) = true, removeFirstOrNull() = null
 * Thread 2: addLast(2) = 2 // this operation is concurrent with both operations in the first thread
 * ```
 */
internal class LockFreeMPSCQueue<E : Any> {
    private val curRef = atomic(Core<E>(Core.INITIAL_CAPACITY))

    // Note: it is not atomic w.r.t. remove operation (remove can transiently fail when isEmpty is false)
    val isEmpty: Boolean get() = curRef.value.isEmpty

    fun close() {
        curRef.loop { cur ->
            if (cur.close()) return // closed this copy
            curRef.compareAndSet(cur, cur.next()) // move to next
        }
    }

    fun addLast(element: E): Boolean {
        curRef.loop { cur ->
            when (cur.addLast(element)) {
                Core.ADD_SUCCESS -> return true
                Core.ADD_CLOSED -> return false
                Core.ADD_FROZEN -> curRef.compareAndSet(cur, cur.next()) // move to next
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun removeFirstOrNull(): E? {
        curRef.loop { cur ->
            val result = cur.removeFirstOrNull()
            if (result !== Core.REMOVE_FROZEN) return result as E?
            curRef.compareAndSet(cur, cur.next())
        }
    }
}

/**
 * Lock-free Multiply-Producer Single-Consumer Queue core.
 * *Note: This queue is NOT linearizable. It provides only quiescent consistency for its operations.*
 *
 * @see LockFreeMPSCQueue
 */
private class LockFreeMPSCQueueCore<E : Any>(private val capacity: Int) {
    private val mask = capacity - 1
    private val nextRef = atomic<Core<E>?>(null)
    private val stateRef = atomic(0L)
    private val array = AtomicReferenceArray<Any?>(capacity)

    init {
        check(mask <= MAX_CAPACITY_MASK)
        check(capacity and mask == 0)
    }

    // Note: it is not atomic w.r.t. remove operation (remove can transiently fail when isEmpty is false)
    val isEmpty: Boolean get() = stateRef.value.withState { head, tail -> head == tail }

    fun close(): Boolean {
        stateRef.update { state ->
            if (state and CLOSED_MASK != 0L) return true // ok - already closed
            if (state and FROZEN_MASK != 0L) return false // frozen -- try next
            state or CLOSED_MASK // try set closed bit
        }
        return true
    }

    // ADD_CLOSED | ADD_FROZEN | ADD_SUCCESS
    fun addLast(element: E): Int {
        stateRef.loop { state ->
            if (state and (FROZEN_MASK or CLOSED_MASK) != 0L) return state.addFailReason() // cannot add
            state.withState { head, tail ->
                // there could be one REMOVE element beyond head that we cannot stump up,
                // so we check for full queue with an extra margin of one element
                if ((tail + 2) and mask == head and mask) return ADD_FROZEN // overfull, so do freeze & copy
                val newTail = (tail + 1) and MAX_CAPACITY_MASK
                if (stateRef.compareAndSet(state, state.updateTail(newTail))) {
                    // successfully added
                    array[tail and mask] = element
                    // could have been frozen & copied before this item was set -- correct it by filling placeholder
                    var cur = this
                    while (true) {
                        if (cur.stateRef.value and FROZEN_MASK == 0L) break // all fine -- not frozen yet
                        cur = cur.next().fillPlaceholder(tail, element) ?: break
                    }
                    return ADD_SUCCESS // added successfully
                }
            }
        }
    }

    private fun fillPlaceholder(index: Int, element: E): Core<E>? {
        val old = array.get(index and mask)
        /*
         * addLast actions:
         * 1) Commit tail slot
         * 2) Write element to array slot
         * 3) Check for array copy
         *
         * If copy happened between 2 and 3 then the consumer might have consumed our element,
         * then another producer might have written its placeholder in our slot, so we should
         * perform *unique* check that current placeholder is our to avoid overwriting another producer placeholder
         */
        if (old is Placeholder && old.index == index) {
            array.set(index and mask, element)
            // we've corrected missing element, should check if that propagated to further copies, just in case
            return this
        }
        // it is Ok, no need for further action
        return null
    }

    // SINGLE CONSUMER
    // REMOVE_FROZEN | null (EMPTY) | E (SUCCESS)
    fun removeFirstOrNull(): Any? {
        stateRef.loop { state ->
            if (state and FROZEN_MASK != 0L) return REMOVE_FROZEN // frozen -- cannot modify
            state.withState { head, tail ->
                if ((tail and mask) == (head and mask)) return null // empty
                // because queue is Single Consumer, then element == null|Placeholder can only be when add has not finished yet
                val element = array[head and mask] ?: return null
                if (element is Placeholder) return null // same story -- consider it not added yet
                // we cannot put null into array here, because copying thread could replace it with Placeholder and that is a disaster
                val newHead = (head + 1) and MAX_CAPACITY_MASK
                if (stateRef.compareAndSet(state, state.updateHead(newHead))) {
                    array[head and mask] = null // now can safely put null (state was updated)
                    return element // successfully removed in fast-path
                }
                // Slow-path for remove in case of interference
                var cur = this
                while (true) {
                    @Suppress("UNUSED_VALUE")
                    cur = cur.removeSlowPath(head, newHead) ?: return element
                }
            }
        }
    }

    private fun removeSlowPath(oldHead: Int, newHead: Int): Core<E>? {
        stateRef.loop { state ->
            state.withState { head, _ ->
                check(head == oldHead) { "This queue can have only one consumer" }
                if (state and FROZEN_MASK != 0L) {
                    // state was already frozen, so removed element was copied to next
                    return next() // continue to correct head in next
                }
                if (stateRef.compareAndSet(state, state.updateHead(newHead))) {
                    array[head and mask] = null // now can safely put null (state was updated)
                    return null
                }
            }
        }
    }

    fun next(): LockFreeMPSCQueueCore<E> = allocateOrGetNextCopy(markFrozen())

    private fun markFrozen(): Long =
        stateRef.updateAndGet { state ->
            if (state and FROZEN_MASK != 0L) return state // already marked
            state or FROZEN_MASK
        }

    private fun allocateOrGetNextCopy(state: Long): Core<E> {
        nextRef.loop { next ->
            if (next != null) return next // already allocated & copied
            nextRef.compareAndSet(null, allocateNextCopy(state))
        }
    }

    private fun allocateNextCopy(state: Long): Core<E> {
        val next = LockFreeMPSCQueueCore<E>(capacity * 2)
        state.withState { head, tail ->
            var index = head
            while (index and mask != tail and mask) {
                // replace nulls with placeholders on copy
                next.array[index and next.mask] = array[index and mask] ?: Placeholder(index)
                index++
            }
            next.stateRef.value = state wo FROZEN_MASK
        }
        return next
    }

    // Instance of this class is placed into array when we have to copy array, but addLast is in progress --
    // it had already reserved a slot in the array (with null) and have not yet put its value there.
    // Placeholder keeps the actual index (not masked) to distinguish placeholders on different wraparounds of array
    private class Placeholder(@JvmField val index: Int)

    companion object {
        internal const val INITIAL_CAPACITY = 8

        private const val CAPACITY_BITS = 30
        private const val MAX_CAPACITY_MASK = (1 shl CAPACITY_BITS) - 1
        private const val HEAD_SHIFT = 0
        private const val HEAD_MASK = MAX_CAPACITY_MASK.toLong() shl HEAD_SHIFT
        private const val TAIL_SHIFT = HEAD_SHIFT + CAPACITY_BITS
        private const val TAIL_MASK = MAX_CAPACITY_MASK.toLong() shl TAIL_SHIFT

        private const val FROZEN_SHIFT = TAIL_SHIFT + CAPACITY_BITS
        private const val FROZEN_MASK = 1L shl FROZEN_SHIFT
        private const val CLOSED_SHIFT = FROZEN_SHIFT + 1
        private const val CLOSED_MASK = 1L shl CLOSED_SHIFT

        @JvmField
        internal val REMOVE_FROZEN = object {
            override fun toString() = "REMOVE_FROZEN"
        }

        internal const val ADD_SUCCESS = 0
        internal const val ADD_FROZEN = 1
        internal const val ADD_CLOSED = 2

        private infix fun Long.wo(other: Long) = this and other.inv()
        private fun Long.updateHead(newHead: Int) = (this wo HEAD_MASK) or (newHead.toLong() shl HEAD_SHIFT)
        private fun Long.updateTail(newTail: Int) = (this wo TAIL_MASK) or (newTail.toLong() shl TAIL_SHIFT)

        private inline fun <T> Long.withState(block: (head: Int, tail: Int) -> T): T {
            val head = ((this and HEAD_MASK) shr HEAD_SHIFT).toInt()
            val tail = ((this and TAIL_MASK) shr TAIL_SHIFT).toInt()
            return block(head, tail)
        }

        // FROZEN | CLOSED
        private fun Long.addFailReason(): Int = if (this and CLOSED_MASK != 0L) ADD_CLOSED else ADD_FROZEN
    }
}
