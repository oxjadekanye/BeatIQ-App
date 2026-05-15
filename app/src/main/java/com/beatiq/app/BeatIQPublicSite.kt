package com.beatiq.app

/**
 * Public marketing and legal pages (no API dependency).
 * Keep in sync with [https://www.beatiq.co.uk](https://www.beatiq.co.uk).
 */
object BeatIQPublicSite {
    const val BASE_URL: String = "https://www.beatiq.co.uk"
    const val PRIVACY_POLICY_URL: String = "$BASE_URL/privacy-policy/"
    const val TERMS_URL: String = "$BASE_URL/terms-and-conditions/"
    const val COOKIE_POLICY_URL: String = "$BASE_URL/cookie-policy/"
    const val DELETE_ACCOUNT_URL: String = "$BASE_URL/delete-account/"
}
