package com.mightyfrog.stratagemcaller.macro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class MacroDirection { UP, DOWN, LEFT, RIGHT }

interface MacroOutput {
    suspend fun activate()
    suspend fun direction(direction: MacroDirection)
    suspend fun releaseDirection(direction: MacroDirection)
    suspend fun releaseAll()
}

class MacroEngine(private val output: MacroOutput) {
    private var job: Job? = null

    fun execute(scope: CoroutineScope, directions: List<Char>, directionDelayMs: Long = 90, onRunning: (Boolean) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                onRunning(true)
                output.activate()
                for (direction in directions) {
                    val value = when (direction.uppercaseChar()) {
                        'U' -> MacroDirection.UP
                        'D' -> MacroDirection.DOWN
                        'L' -> MacroDirection.LEFT
                        'R' -> MacroDirection.RIGHT
                        else -> continue
                    }
                    output.direction(value)
                    delay(directionDelayMs)
                    output.releaseDirection(value)
                    delay(directionDelayMs)
                }
            } catch (_: CancellationException) {
                output.releaseAll()
            } catch (_: Exception) {
                output.releaseAll()
            } finally {
                output.releaseAll()
                onRunning(false)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
