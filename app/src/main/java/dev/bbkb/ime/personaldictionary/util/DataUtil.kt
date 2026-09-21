package dev.bbkb.ime.personaldictionary.util

/**
 * Collection manipulation utilities.
 */
object DataUtil {
    fun <T> moveObjectToFrontOfList(item: T, list: MutableList<T>) {
        val index = list.indexOf(item)
        if (index != -1) {
            list.add(0, list.removeAt(index))
        }
    }
}
