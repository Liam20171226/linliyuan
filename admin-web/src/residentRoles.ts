export const RESIDENT_ROLE_OPTIONS = [
  { value: 'OWNER', label: '业主' },
  { value: 'OWNER_MEMBER', label: '业主成员' },
  { value: 'TENANT', label: '租户' },
  { value: 'TENANT_MEMBER', label: '租户成员' },
] as const

export function residentRoleLabel(role: string) {
  return RESIDENT_ROLE_OPTIONS.find((o) => o.value === role)?.label || role || '—'
}

export interface RoomOption {
  id: number
  label: string
}

/** Flatten /staff/space-tree into room select options. */
export function flattenRoomsFromTree(raw: any[]): RoomOption[] {
  const out: RoomOption[] = []
  for (const b of raw || []) {
    for (const u of b.units || []) {
      for (const f of u.floors || []) {
        for (const r of f.rooms || []) {
          const prefix = [b.name, u.name, f.name].filter(Boolean).join('/')
          out.push({ id: r.id, label: `${prefix}/${r.roomNo}` })
        }
      }
    }
  }
  return out
}
