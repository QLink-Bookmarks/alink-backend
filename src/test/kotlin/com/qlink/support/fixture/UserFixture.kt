package com.qlink.support.fixture

import com.qlink.auth.domain.Role
import com.qlink.user.domain.User

object UserFixture {
    fun createRandomValidUser(allowsReminder: Boolean = true): User =
        User(
            id = RandomFixture.randomId(),
            username = "user-${RandomFixture.randomId()}",
            nickname = RandomFixture.randomSentenceWithMax(50),
            avatarUrl = RandomFixture.randomUrl(),
            avatarEmoji = RandomFixture.randomEmoji(),
            allowsReminder = allowsReminder,
        )

    fun createRandomValidSuperAdmin(): User = createRandomValidUser().copyForTestRole(role = Role.SUPER_ADMIN)
}

fun User.copyForTestRole(role: Role): User =
    User(
        id = id,
        username = username,
        nickname = nickname,
        role = role,
        avatarUrl = avatarUrl,
        avatarEmoji = avatarEmoji,
        theme = theme,
        accent = accent,
        allowsReminder = allowsReminder,
        defaultAiProviderId = defaultAiProviderId,
        defaultModelId = defaultModelId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
