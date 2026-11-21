package com.mightyfrog.stratagemcaller.macro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MacroDirection { UP, DOWN, LEFT, RIGHT }

interface MacroOutput {
    suspend fun activate()
    suspend fun direction(direction: MacroDirection)
    suspend fun releaseDirection(direction: MacroDirection)
    suspend fun releaseAll()
}

class MacroEngine(private val output: MacroOutput) {
    companion object {
        const val DEFAULT_KEY_HOLD_MS = 55L
        const val INTER_KEY_GAP_MS = 25L
    }

    private var job: Job? = null

    fun execute(
        scope: CoroutineScope,
        directions: List<Char>,
        directionDelayMs: Long = DEFAULT_KEY_HOLD_MS,
        onRunning: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                onRunning(true)
                output.activate()
                for ((index, direction) in directions.withIndex()) {
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
                    if (index < directions.lastIndex) delay(INTER_KEY_GAP_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onError(error.message ?: "Controller connection failed.")
            } finally {
                withContext(NonCancellable) {
                    try {
                        output.releaseAll()
                    } catch (error: Exception) {
                        onError(error.message ?: "Could not release controller inputs.")
                    }
                }
                onRunning(false)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
