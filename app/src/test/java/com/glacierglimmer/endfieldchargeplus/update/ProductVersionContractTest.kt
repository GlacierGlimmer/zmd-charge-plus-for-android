package com.glacierglimmer.endfieldchargeplus.update

import com.glacierglimmer.endfieldchargeplus.BuildConfig
import com.glacierglimmer.endfieldchargeplus.core.product.ProductInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProductVersionContractTest {
    @Test fun `installed version shared product metadata and updates agree without build suffixes`() {
        assertEquals("v0.1.1", BuildConfig.VERSION_NAME)
        assertEquals(ProductInfo.VERSION_NAME, BuildConfig.VERSION_NAME)
        assertEquals(ProductInfo.VERSION_CODE, BuildConfig.VERSION_CODE)
        assertEquals(2, BuildConfig.VERSION_CODE)
        assertEquals(UpdateStatus.UP_TO_DATE, UpdateVersions.compare(BuildConfig.VERSION_NAME, "v0.1.1").status)
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, UpdateVersions.compare(BuildConfig.VERSION_NAME, "v0.1.2").status)
    }
}
