package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.CoursePreviewPoint
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeTask
import javax.inject.Inject

/** Fictional preparation examples with separate fixed landmark previews, not reviewed road routes. */
class LocalPracticeCatalog
    @Inject
    constructor() : PracticeCatalog {
        override val goals =
            listOf(
                PracticeGoal("commute", "출퇴근길 익히기", "매일 가야 하는 길을 떠올리며 준비해요."),
                PracticeGoal("shopping", "생활권 장보기", "가까운 일상 목적지부터 차근차근 준비해요."),
            )
        override val courses =
            listOf(
                PracticeCourse(
                    id = "neighborhood",
                    version = 1,
                    briefingVersion = 1,
                    title = "생활권 한 바퀴",
                    summary = "출발부터 돌아오는 순간까지, 짧은 이동의 순서를 살펴봐요.",
                    sections = listOf("출발 준비", "동네 길 살펴보기", "목적지 도착", "돌아와 주차"),
                    tasks =
                        listOf(
                            PracticeTask("sequence", "이동 순서 떠올리기", "출발과 도착 사이에 어떤 구간이 있을지 한 번 떠올려 보세요."),
                            PracticeTask("arrival", "도착 후 할 일 생각하기", "도착했을 때 확인하고 싶은 일 하나를 정해 보세요."),
                        ),
                    goalIds = setOf("commute", "shopping"),
                    mapPreview =
                        CourseMapPreview(
                            id = "seoul-city-hall-landmarks",
                            version = 1,
                            title = "서울 시청 주변",
                            points =
                                listOf(
                                    CoursePreviewPoint("seoul-city-hall", "서울특별시청", 37.5666904, 126.9784222, 198561926L, 28),
                                    CoursePreviewPoint("seoul-plaza", "서울광장", 37.5656223, 126.9779906, 370019676L, 8),
                                    CoursePreviewPoint("daehanmun", "대한문", 37.5650316, 126.9765496, 377289797L, 8),
                                ),
                        ),
                ),
                PracticeCourse(
                    id = "intersections",
                    version = 1,
                    briefingVersion = 1,
                    title = "교차로 흐름 익히기",
                    summary = "구간을 나눠 보고, 미리 생각해 둘 장면 하나를 골라요.",
                    sections = listOf("출발 준비", "교차로가 있는 구간", "목적지 접근", "도착 후 정리"),
                    tasks =
                        listOf(
                            PracticeTask("scene", "낯선 장면 하나 떠올리기", "이동 중 낯설게 느껴질 장면 하나를 미리 떠올려 보세요."),
                            PracticeTask("review", "다시 살펴볼 구간 정하기", "이동 후 차분히 돌아보고 싶은 구간 하나를 정해 보세요."),
                        ),
                    goalIds = setOf("commute", "shopping"),
                    mapPreview =
                        CourseMapPreview(
                            id = "gwanghwamun-landmarks",
                            version = 1,
                            title = "광화문 주변",
                            points =
                                listOf(
                                    CoursePreviewPoint("gwanghwamun", "광화문", 37.5759283, 126.9768176, 170199440L, 22),
                                    CoursePreviewPoint("sejong-center", "세종문화회관", 37.5723190, 126.9756030, 543729863L, 14),
                                    CoursePreviewPoint("government-seoul-complex", "정부서울청사", 37.5748313, 126.9749411, 196076051L, 18),
                                ),
                        ),
                ),
            )
    }
