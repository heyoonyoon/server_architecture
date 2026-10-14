export type EntityApi<T, D> = {
  list: () => Promise<T[]>
  add: (draft: D) => Promise<boolean>
  remove: (id: string) => Promise<boolean>
}
