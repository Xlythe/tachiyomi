package eu.kanade.domain.manga.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UpdateMangaTitleTest {

    @Test
    fun `accepts a source title when only Official prefix was removed`() {
        "Official Sakamoto Days".officialPrefixCorrectionFor("Sakamoto Days") shouldBe "Sakamoto Days"
        "  OFFICIAL   Sakamoto Days".officialPrefixCorrectionFor("Sakamoto Days") shouldBe "Sakamoto Days"
        "\uFEFFOfficial\u2060Sakamoto Days".officialPrefixCorrectionFor("Sakamoto Days") shouldBe "Sakamoto Days"
    }

    @Test
    fun `preserves a saved subtitle when the source now exposes only the base title`() {
        "Official Trinity Seven: 7-Nin no Mahoutsukai"
            .officialPrefixCorrectionFor("Trinity Seven") shouldBe "Trinity Seven: 7-Nin no Mahoutsukai"
        "Official Trinity Seven — 7-Nin no Mahoutsukai"
            .officialPrefixCorrectionFor("Trinity Seven") shouldBe "Trinity Seven — 7-Nin no Mahoutsukai"
    }

    @Test
    fun `preserves a saved subtitle when the source uses an alternate translated subtitle`() {
        "Official Trinity Seven: 7-Nin no Mahoutsukai"
            .officialPrefixCorrectionFor("Trinity Seven: The Seven Magicians") shouldBe
            "Trinity Seven: 7-Nin no Mahoutsukai"
    }

    @Test
    fun `rejects unrelated favorite title changes`() {
        "Officially Yours".officialPrefixCorrectionFor("Yours") shouldBe null
        "The Official Guide".officialPrefixCorrectionFor("The Guide") shouldBe null
        "Official Sakamoto Days".officialPrefixCorrectionFor("Sakamoto Holidays") shouldBe null
        "Official Trinity Sevenfold".officialPrefixCorrectionFor("Trinity Seven") shouldBe null
        "Official Trinity Sevenfold: Side Story"
            .officialPrefixCorrectionFor("Trinity Seven: The Seven Magicians") shouldBe null
        "Sakamoto Days".officialPrefixCorrectionFor("Sakamoto Days") shouldBe null
    }
}
