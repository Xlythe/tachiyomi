package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SourceFilterState {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serialize(filters: FilterList): String {
        return json.encodeToString(filters.toPersistedStates())
    }

    fun restore(filters: FilterList, serializedState: String): FilterList {
        if (serializedState.isBlank()) return filters

        val savedStates = runCatching {
            json.decodeFromString<List<PersistedFilterState>>(serializedState)
        }.getOrNull() ?: return filters

        val savedByPath = savedStates.associateBy(PersistedFilterState::path)
        filters.visitFilters { path, filter ->
            val saved = savedByPath[path] ?: return@visitFilters
            if (saved.name != filter.name || saved.type != filter.typeName()) return@visitFilters

            when (filter) {
                is Filter.Text -> filter.state = saved.value.orEmpty()
                is Filter.CheckBox -> saved.value?.toBooleanStrictOrNull()?.let { filter.state = it }
                is Filter.TriState -> saved.value
                    ?.toIntOrNull()
                    ?.takeIf { it in Filter.TriState.STATE_IGNORE..Filter.TriState.STATE_EXCLUDE }
                    ?.let { filter.state = it }
                is Filter.Select<*> -> saved.value
                    ?.toIntOrNull()
                    ?.takeIf { it in filter.values.indices }
                    ?.let { filter.state = it }
                is Filter.Sort -> {
                    filter.state = saved.value
                        ?.toIntOrNull()
                        ?.takeIf { it in filter.values.indices }
                        ?.let { Filter.Sort.Selection(it, saved.ascending ?: true) }
                }
                else -> Unit
            }
        }
        return filters
    }

    private fun FilterList.toPersistedStates(): List<PersistedFilterState> {
        return buildList {
            visitFilters { path, filter ->
                val state = when (filter) {
                    is Filter.Text -> PersistedFilterState(path, filter.name, filter.typeName(), filter.state)
                    is Filter.CheckBox -> PersistedFilterState(path, filter.name, filter.typeName(), filter.state.toString())
                    is Filter.TriState -> PersistedFilterState(path, filter.name, filter.typeName(), filter.state.toString())
                    is Filter.Select<*> -> PersistedFilterState(path, filter.name, filter.typeName(), filter.state.toString())
                    is Filter.Sort -> PersistedFilterState(
                        path = path,
                        name = filter.name,
                        type = filter.typeName(),
                        value = filter.state?.index?.toString(),
                        ascending = filter.state?.ascending,
                    )
                    else -> null
                }
                state?.let(::add)
            }
        }
    }

    private fun FilterList.visitFilters(
        parentPath: String = "",
        visitor: (path: String, filter: Filter<*>) -> Unit,
    ) {
        forEachIndexed { index, filter ->
            val path = if (parentPath.isEmpty()) index.toString() else "$parentPath.$index"
            visitor(path, filter)
            if (filter is Filter.Group<*>) {
                FilterList(filter.state.filterIsInstance<Filter<*>>())
                    .visitFilters(path, visitor)
            }
        }
    }

    private fun Filter<*>.typeName(): String = when (this) {
        is Filter.Text -> "text"
        is Filter.CheckBox -> "checkbox"
        is Filter.TriState -> "tristate"
        is Filter.Select<*> -> "select"
        is Filter.Sort -> "sort"
        is Filter.Group<*> -> "group"
        is Filter.Header -> "header"
        is Filter.Separator -> "separator"
    }

    @Serializable
    private data class PersistedFilterState(
        val path: String,
        val name: String,
        val type: String,
        val value: String? = null,
        val ascending: Boolean? = null,
    )
}
