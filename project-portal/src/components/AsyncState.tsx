export function LoadingState() {
  return <div className="project-grid" aria-label="正在加载能力目录">{[1, 2, 3].map((item) => <div className="skeleton" key={item} />)}</div>
}

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="state-card" role="alert">
      <div className="state-icon" aria-hidden="true">!</div>
      <h2>能力目录暂时无法加载</h2>
      <p>{message}</p>
      <button type="button" onClick={onRetry}>重新加载</button>
    </div>
  )
}

export function EmptyState({ filtered, onClear }: { filtered: boolean; onClear: () => void }) {
  return (
    <div className="state-card">
      <div className="state-icon" aria-hidden="true">◇</div>
      <h2>{filtered ? '没有匹配的能力项目' : '当前尚未发布公开能力'}</h2>
      <p>{filtered ? '试试更短的关键词或选择其他类别。' : '目录更新后，项目会自动出现在这里。'}</p>
      {filtered && <button type="button" onClick={onClear}>清除筛选</button>}
    </div>
  )
}
