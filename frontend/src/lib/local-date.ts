function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** Local calendar date as `YYYY-MM-DD`. */
export function toLocalDateString(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** ISO instant for 23:59:59.999 of the picked `YYYY-MM-DD` day in the local time zone. */
export function endOfLocalDayIso(day: string): string {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d, 23, 59, 59, 999).toISOString()
}
