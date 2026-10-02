package com.example.core

/**
 * Represents the fundamental operational state of Volume Guard.
 *
 * Guard State is strictly binary:
 * - OFF: Normal Android behavior. No interception.
 * - ON: Media volume is locked to 0. Physical Volume Up serves as user override.
 */
enum class GuardState {
    OFF,
    ON;

    val isEnabled: Boolean
        get() = this == ON
}
