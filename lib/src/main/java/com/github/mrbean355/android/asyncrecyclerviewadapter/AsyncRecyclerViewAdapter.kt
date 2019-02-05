package com.github.mrbean355.android.asyncrecyclerviewadapter

import android.os.Handler
import android.os.Looper
import android.support.v4.util.ArraySet
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors

abstract class AsyncRecyclerViewAdapter<T, VH : RecyclerView.ViewHolder>(
        private val diffCallback: DiffUtil.ItemCallback<T>,
        private val maxSelections: Int) : RecyclerView.Adapter<VH>() {

    /** Full list of the maintained items. */
    private var sourceItems: List<T> = emptyList()
    /** Items that are currently being displayed. */
    private var displayedItems: List<T> = emptyList()
    /** Items that are currently selected. */
    private val selectedItems: MutableSet<T> = mutableSetOf()
    /** Pending list updates. */
    private val updateQueue: Queue<List<T>> = ConcurrentLinkedQueue<List<T>>()

    /**
     * Override to customise item sorting.
     *
     * Performs no sorting by default.
     */
    protected open fun compareItems(lhs: T, rhs: T): Int {
        return 0
    }

    /**
     * Override to implement item filtering.
     *
     * Calling [filter] without providing custom behaviour here will cause no items to be displayed.
     * [query] will never be empty.
     */
    protected open fun testItem(item: T, query: String): Boolean {
        return false
    }

    /**
     * Set the adapter's items.
     *
     * [compareItems] will be used to compare items.
     * Resets any filtering done via [filter].
     * Ignores any `null` elements in the collection.
     */
    fun setItems(items: Collection<T>?) {
        this.sourceItems = items.orEmpty()
                .filter { it != null }
                .sortedWith(Comparator { o1, o2 -> compareItems(o1, o2) })
        publishList(sourceItems)
    }

    /**
     * @return a set of currently selected items.
     */
    fun getSelectedItems(): Set<T> {
        return ArraySet(selectedItems)
    }

    /**
     * Set the currently selected items.
     */
    fun setSelectedItems(selection: Collection<T>) {
        if (selection.size > maxSelections) {
            throw IllegalArgumentException("Tried to select more items (${selection.size}) than max ($maxSelections)")
        }
        val changedItems = disjunctiveUnion(selectedItems, selection)
        selectedItems.clear()
        selectedItems.addAll(selection)
        changedItems.map { displayedItems.indexOf(it) }
                .filter { it != -1 }
                .forEach {
                    notifyItemChanged(it)
                }
    }

    /**
     * Filter the displayed items based on some [query].
     *
     * A `null` or empty [query] causes all items to be displayed.
     */
    fun filter(query: String?) {
        if (query.isNullOrEmpty()) {
            publishList(sourceItems)
            return
        }
        val filtered = this.sourceItems.filter { testItem(it, query) }
        publishList(filtered)
    }

    /**
     * @return the currently displayed item at [position].
     */
    fun getItemAt(position: Int) = displayedItems[position]

    /**
     * @return `true` if the displayed item at [position] is currently selected, `false` otherwise.
     */
    fun isItemSelected(position: Int): Boolean {
        return selectedItems.contains(getItemAt(position))
    }

    override fun getItemCount() = displayedItems.size

    /**
     * Trigger a selection event; selecting the item at [adapterPosition] if its not selected, and deselecting it otherwise.
     */
    protected fun onItemClicked(adapterPosition: Int) {
        val item = getItemAt(adapterPosition)
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
        } else if (selectedItems.size < maxSelections) {
            selectedItems.add(item)
        }
        notifyItemChanged(adapterPosition)
    }

    /** Update the displayed list, doing diff calculations in the background. */
    private fun publishList(update: List<T>) {
        updateQueue.add(update)
        if (updateQueue.size == 1) {
            // No other updates in progress; process this update.
            processQueue()
        }
    }

    /** Process the next update in the [updateQueue]. */
    private fun processQueue() {
        val newList = updateQueue.remove()
        doInBackground {
            val result = DiffUtil.calculateDiff(DiffCallback(displayedItems, newList, diffCallback))
            doOnMain {
                this.displayedItems = newList
                result.dispatchUpdatesTo(this)
                if (updateQueue.isNotEmpty()) {
                    processQueue()
                }
            }
        }
    }

    private companion object {
        private val MAIN_THREAD_EXECUTOR = MainThreadExecutor()
        private val BACKGROUND_THREAD_EXECUTOR = Executors.newSingleThreadExecutor()

        /** Execute something on the main thread. */
        private fun doOnMain(block: () -> Unit) {
            MAIN_THREAD_EXECUTOR.execute(block)
        }

        /** Execute something on a background thread. */
        private fun doInBackground(block: () -> Unit) {
            BACKGROUND_THREAD_EXECUTOR.execute(block)
        }

        /** Return a collection of items that are in either [a] or [b], but not both. */
        private fun <T> disjunctiveUnion(a: Collection<T>, b: Collection<T>): Collection<T> {
            val result = a.union(b).toMutableSet()
            val intersect = a.intersect(b)
            result.removeAll(intersect)
            return result
        }
    }

    /** [DiffUtil.Callback] which delegates item checks to another [callback]. */
    private class DiffCallback<T>(private val oldList: List<T>, private val newList: List<T>, private val callback: DiffUtil.ItemCallback<T>) : DiffUtil.Callback() {

        override fun getOldListSize() = oldList.size

        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return callback.areItemsTheSame(oldList[oldItemPosition], newList[newItemPosition])
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return callback.areContentsTheSame(oldList[oldItemPosition], newList[newItemPosition])
        }
    }

    /** Executor which executes things on the main thread. */
    private class MainThreadExecutor : Executor {
        private val handler = Handler(Looper.getMainLooper())

        override fun execute(command: Runnable?) {
            handler.post(command)
        }
    }
}