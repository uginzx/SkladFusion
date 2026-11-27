package app.skladfusion.ui

import androidx.compose.runtime.mutableStateListOf

sealed interface Screen {
    data object Orders : Screen
    data class Pick(val guid: String) : Screen
    data class Review(val guid: String) : Screen
    data class Journal(val guid: String) : Screen
    data class Ship(val guid: String) : Screen
    data class Product(val guid: String) : Screen
    data object Find : Screen
    data object Place : Screen
    data object Cells : Screen
    data object Menu : Screen
    data object NewOrder : Screen
    data object Tasks : Screen
    data class Task(val id: String) : Screen
    data object NewTask : Screen
    data object Inventories : Screen
    data object NewInventory : Screen
    data class Inventory(val id: String) : Screen
    /** Неизвестный товар в пересчёте id: barcode — отсканированный код, cell — текущая ячейка (0 — нет). */
    data class InvUnknown(val id: String, val barcode: String = "", val cell: Int = 0) : Screen
}

/** Простой стек экранов: приложение маленькое, библиотека навигации не нужна. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Orders)
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}
