interface SearchFiltersProps {
  query: string;
  category: string;
  categories: string[];
  onlyAvailable: boolean;
  availableCount: number;
  filtered: boolean;
  onQuery: (value: string) => void;
  onCategory: (value: string) => void;
  onOnlyAvailable: (value: boolean) => void;
  onClear: () => void;
}

export function SearchFilters({
  query,
  category,
  categories,
  onlyAvailable,
  availableCount,
  filtered,
  onQuery,
  onCategory,
  onOnlyAvailable,
  onClear,
}: SearchFiltersProps) {
  return (
    <div className="filters" aria-label="能力筛选">
      <label className="search-field">
        <span className="sr-only">搜索能力项目</span>
        <svg
          className="search-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          aria-hidden="true"
        >
          <circle cx="10.5" cy="10.5" r="6.5" />
          <path d="m16 16 4 4" />
        </svg>
        <input
          type="search"
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          placeholder="搜索项目、能力或标签"
        />
      </label>
      <div className="filter-actions">
        <label className={`availability-filter${onlyAvailable ? ' active' : ''}`}>
          <input
            type="checkbox"
            checked={onlyAvailable}
            onChange={(event) => onOnlyAvailable(event.target.checked)}
          />
          <span>仅看可用项目</span>
          <span className="available-count" aria-label={`${availableCount} 个可用项目`}>
            {availableCount}
          </span>
        </label>
        {filtered && (
          <button className="clear-filters" type="button" onClick={onClear}>
            清除筛选
          </button>
        )}
      </div>
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
  );
}
