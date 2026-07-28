package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceFilterStateTest {

    @Test
    fun `restores persisted source filters into a fresh filter list`() {
        val original = testFilters().also {
            (it[0] as TextFilter).state = "tag one,-tag two"
            (it[1] as SelectFilter).state = 2
            (it[2] as SortFilter).state = Filter.Sort.Selection(1, false)
            ((it[3] as GroupFilter).state[0] as CheckBoxFilter).state = true
        }

        val restored = SourceFilterState.restore(
            filters = testFilters(),
            serializedState = SourceFilterState.serialize(original),
        )

        (restored[0] as TextFilter).state shouldBe "tag one,-tag two"
        (restored[1] as SelectFilter).state shouldBe 2
        (restored[2] as SortFilter).state shouldBe Filter.Sort.Selection(1, false)
        ((restored[3] as GroupFilter).state[0] as CheckBoxFilter).state shouldBe true
    }

    @Test
    fun `ignores persisted values when a source changes its filter definition`() {
        val original = testFilters().also {
            (it[0] as TextFilter).state = "saved"
        }
        val changed = FilterList(TextFilter("Renamed"))

        SourceFilterState.restore(changed, SourceFilterState.serialize(original))

        (changed[0] as TextFilter).state shouldBe ""
    }

    @Test
    fun `ignores malformed persisted state`() {
        val filters = testFilters()

        SourceFilterState.restore(filters, "not-json")

        (filters[0] as TextFilter).state shouldBe ""
    }

    private fun testFilters() = FilterList(
        TextFilter("Tags"),
        SelectFilter("Language"),
        SortFilter("Sort"),
        GroupFilter("Options", listOf(CheckBoxFilter("Completed"))),
    )

    private class TextFilter(name: String) : Filter.Text(name)
    private class SelectFilter(name: String) : Filter.Select<String>(name, arrayOf("A", "B", "C"))
    private class SortFilter(name: String) : Filter.Sort(name, arrayOf("Recent", "Popular"))
    private class CheckBoxFilter(name: String) : Filter.CheckBox(name)
    private class GroupFilter(name: String, filters: List<Filter<*>>) : Filter.Group<Filter<*>>(name, filters)
}
