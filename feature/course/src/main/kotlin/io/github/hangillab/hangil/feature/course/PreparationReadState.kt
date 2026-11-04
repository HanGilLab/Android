package io.github.hangillab.hangil.feature.course

internal enum class PreparationReadState {
    LOADING,
    READY,
    OUTDATED,
    FAILED,
    ;

    val allowsEditing: Boolean get() = this == READY || this == OUTDATED
}
