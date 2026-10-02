import type { ThemeConfig } from 'antd'
import { colors } from './colors'

/** 唯一 ThemeConfig(经 ConfigProvider)。色值取自 colors.ts 单一来源。 */
export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: colors.primary,
    colorInfo: colors.primary,
    colorSuccess: colors.success,
    colorWarning: colors.warning,
    colorError: colors.error,
    colorBgLayout: colors.bgLayout,
    colorBgContainer: '#FFFFFF',
    colorText: colors.text,
    colorTextSecondary: colors.textSecondary,
    colorBorderSecondary: colors.border,
    borderRadius: 8,
    borderRadiusLG: 12,
    fontSize: 14,
    controlHeight: 36,
    controlHeightLG: 40,
    wireframe: false,
  },
  components: {
    Layout: { headerBg: '#FFFFFF', siderBg: '#FFFFFF', bodyBg: colors.bgLayout, headerHeight: 56, headerPadding: '0 20px' },
    Menu: { itemSelectedBg: colors.primarySoft, itemSelectedColor: colors.primary, itemBorderRadius: 8, itemMarginInline: 8 },
    Table: { headerBg: colors.bgSubtle, headerColor: '#475467', rowHoverBg: colors.bgSubtle },
    Card: { headerBg: 'transparent', headerFontSize: 15 },
    Segmented: { itemSelectedBg: '#FFFFFF' },
  },
}

/** 治理工作空间在同一设计系统内调整密度与对比度，不影响既有登录及旧工作区。 */
export const governanceTheme: ThemeConfig = {
  token: { colorTextSecondary: colors.governanceMuted, controlHeight: 38, borderRadius: 8 },
  components: { Table: { cellPaddingBlock: 18, headerBg: colors.bgSubtle }, Card: { headerFontSize: 16 }, Modal: { borderRadiusLG: 16, titleFontSize: 18 } },
}
