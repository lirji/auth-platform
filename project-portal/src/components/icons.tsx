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

function BenefitIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M4 11h16v9H4z"/><path d="M4 11V8h16v3"/><path d="M12 8v12"/><path d="M12 8c-2-3-5-3-5 0 2.5 0 5 0 5 0"/><path d="M12 8c2-3 5-3 5 0-2.5 0-5 0-5 0"/></svg>
}

function MarketingIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M4 9v6l12 4V5z"/><path d="M16 8.5c2 1 3.5 2.2 3.5 3.5s-1.5 2.5-3.5 3.5"/><path d="M9 12h.01"/></svg>
}

function TradeIcon(props: IconProps) {
  return <svg {...common} {...props}><rect x="3" y="7" width="18" height="13" rx="2"/><path d="M8 7V5a4 4 0 0 1 8 0v2"/><path d="M8 12h8M8 16h5"/></svg>
}

function WarehouseIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M3 10 12 4l9 6v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"/><path d="M9 20v-6h6v6"/><path d="M4 10h16"/></svg>
}

function IamIcon(props: IconProps) {
  return <svg {...common} {...props}><path d="M12 3 4.5 6v5.5c0 4.4 3 7.8 7.5 9.5 4.5-1.7 7.5-5.1 7.5-9.5V6z"/><circle cx="12" cy="10" r="2.2"/><path d="M8.5 16c.8-1.6 2-2.4 3.5-2.4s2.7.8 3.5 2.4"/></svg>
}

function OaIcon(props: IconProps) {
  return <svg {...common} {...props}><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 7h8M8 11h8M8 15h5"/><path d="M16 16.5v3l2-1.2 2 1.2v-3"/></svg>
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
              : name === 'benefit'
                ? BenefitIcon
                : name === 'marketing'
                  ? MarketingIcon
                  : name === 'trade'
                    ? TradeIcon
                    : name === 'warehouse'
                      ? WarehouseIcon
                      : name === 'iam'
                        ? IamIcon
                        : name === 'oa'
                          ? OaIcon
                          : DefaultIcon
  return <Icon width={28} height={28} />
}
