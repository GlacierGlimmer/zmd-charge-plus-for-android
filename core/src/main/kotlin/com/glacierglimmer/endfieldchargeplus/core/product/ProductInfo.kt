package com.glacierglimmer.endfieldchargeplus.core.product

/**
 * Product facts shared by the settings UI, the foreground notification, the about page and the
 * documentation. Kept identical to the desktop editions wherever the value is not platform
 * specific.
 */
object ProductInfo {
    const val NAME = "Endfield Charge Plus for Android"
    const val SHORT_NAME = "ECP"
    // Change the product version only when the user requests it.
    const val VERSION_NAME = "v0.1.1"
    const val VERSION_CODE = 2
    const val AUTHOR = "GlacierGlimmer / 冰川雪貓"
    const val WEBSITE = "zmd-bar.x-neko.com"
    const val GITHUB_REPOSITORY = "GlacierGlimmer/zmd-charge-plus-for-android"
    const val GITHUB_URL = "https://github.com/GlacierGlimmer/zmd-charge-plus-for-android"
    const val DESKTOP_REPOSITORY = "GlacierGlimmer/zmd-charge-plus"
    const val UPSTREAM_PROJECT = "QinAnze/zmd-charge"
    const val UPSTREAM_URL = "https://github.com/QinAnze/zmd-charge"
    const val LICENSE_NAME = "MIT License"
    const val ANDROID_PACKAGE_NAME = "com.glacierglimmer.endfieldchargeplus"

    /** Short branded variant used by the notification and the settings header. */
    val displayNameWithVersion: String get() = "$NAME $VERSION_NAME"
}
