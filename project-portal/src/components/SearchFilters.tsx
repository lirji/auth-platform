interface SearchFiltersProps {
  query: string
  category: string
  categories: string[]
  onQuery: (value: string) => void
  onCategory: (value: string) => void
}

export function SearchFilters({ query, category, categories, onQuery, onCategory }: SearchFiltersProps) {
  return (
    <div className="filters" aria-label="能力筛选">
      <label className="search-field">
        <span className="sr-only">搜索能力项目</span>
        <span className="search-icon" aria-hidden="true">⌕</span>
        <input
          type="search"
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          placeholder="搜索项目、能力或标签"
        />
      </label>
      <div className="category-list" role="group" aria-label="按类别筛选">
        {['全部', ...categories].map((item) => (
          <button
            key={item}
            type="button"
            className={item === category ? 'active' : ''}
            aria-pressed={item === category}
            onClick={() => onCategory(item)}
          >
            {item}
          </button>
        ))}
      </div>
    </div>
  )
}
