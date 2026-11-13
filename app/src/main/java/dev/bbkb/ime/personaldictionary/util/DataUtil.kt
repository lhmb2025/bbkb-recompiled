package dev.bbkb.ime.personaldictionary.util

object DataUtil {
    /** @param list reordered in place when it contains [item] */
    fun <T> moveObjectToFrontOfList(item: T, list: MutableList<T>) {
        val index = list.indexOf(item)
        if (index != -1) {
            list.add(0, list.removeAt(index))
        }
    }
}
