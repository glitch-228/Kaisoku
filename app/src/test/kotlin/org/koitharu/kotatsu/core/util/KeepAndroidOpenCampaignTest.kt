package org.koitharu.kotatsu.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAndroidOpenCampaignTest {
	@Test fun `uses translated campaign pages when available`() {
		assertEquals("https://keepandroidopen.org/ru/", KeepAndroidOpenCampaign.url("ru-RU"))
		assertEquals("https://keepandroidopen.org/zh-CN/", KeepAndroidOpenCampaign.url("zh-CN"))
		assertEquals("https://keepandroidopen.org/zh-TW/", KeepAndroidOpenCampaign.url("zh-Hant"))
	}

	@Test fun `unknown languages fall back to English`() {
		assertEquals("https://keepandroidopen.org/", KeepAndroidOpenCampaign.url("xx"))
	}
}
