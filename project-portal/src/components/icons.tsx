import type { SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement>

const common: IconProps = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
}

function AiIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M8 4h8l4 6-8 10L4 10z"/><path d="m8 4 4 16 4-16M4 10h16"/></svg>
}

function RecommendationIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M4 19V9M10 19V5M16 19v-7M22 19H2"/><path d="m4 6 5-3 6 5 6-5"/></svg>
}

function RulesIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M5 4h14v16H5z"/><path d="M8 8h8M8 12h5M8 16h3"/><path d="m15 15 1.5 1.5L20 13"/></svg>
}

function RiskIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M12 3 4.5 6v5.5c0 4.4 3 7.8 7.5 9.5 4.5-1.7 7.5-5.1 7.5-9.5V6z"/><path d="m8 13 2.3-2.3 2.2 1.8L16 9"/></svg>
}

function WorkflowIcon(props: IconProps) {
  return <svg {...common} {...props}><rect x="3" y="3" width="6" height="5" rx="1"/><rect x="15" y="16" width="6" height="5" rx="1"/><path d="M9 5.5h3a4 4 0 0 1 4 4V12M15 18.5h-3a4 4 0 0 1-4-4V12"/><path d="m14 10 2 2 2-2M10 14l-2-2-2 2"/></svg>
}

function ReconciliationIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M4 7h12M4 12h9M4 17h7"/><path d="m17 15 2 2 3-4"/><path d="M4 4h16v16H4z"/></svg>
}

function DefaultIcon(props: IconProps) {
  return <svg {...common} {...props}><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
}

export function ProjectIcon({ name }: { name: string }) {
  const Icon = name === 'ai'
    ? AiIcon
    : name === 'recommendation'
      ? RecommendationIcon
      : name === 'rules'
        ? RulesIcon
      : name === 'risk'
          ? RiskIcon
          : name === 'workflow'
            ? WorkflowIcon
            : name === 'reconciliation'
              ? ReconciliationIcon
              : DefaultIcon
  return <Icon width={28} height={28} />
}
