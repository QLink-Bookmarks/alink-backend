package com.qlink.support.fixture

import com.qlink.auth.dto.AuthPlatform
import com.qlink.auth.dto.SignInRequest

object AuthRequestFixture {
    fun createKakaoSignInRequest(platform: AuthPlatform): SignInRequest =
        SignInRequest(
            provider = "kakao",
            token = RandomFixture.randomSentenceWithMax(50),
            platform = platform,
        )
}
