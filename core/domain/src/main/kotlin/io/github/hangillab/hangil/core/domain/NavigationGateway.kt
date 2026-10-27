package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.StateFlow

/** SDK의 목적지 요청 API는 실제 연동 단계에서 추가한다. 현재는 연결 가능 여부만 제공한다. */
interface NavigationGateway {
    val connection: StateFlow<ConnectionState>
}
