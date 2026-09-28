/** 金额展示：统一保留两位小数 */
export function formatMoney(v: unknown): string {
  if (v == null || v === '') return '0.00'
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return '0.00'
  return n.toFixed(2)
}
