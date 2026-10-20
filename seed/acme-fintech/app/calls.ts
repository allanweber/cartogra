export interface CallConfig {
  to: string
  enabled: boolean
}

export interface CallSchedulerOptions {
  calls: CallConfig[]
  intervalMs: number
  invoke: (target: string) => Promise<void>
  onError?: (target: string, error: unknown) => void
}

export class CallScheduler {
  private readonly enabled = new Map<string, boolean>()
  private readonly options: CallSchedulerOptions
  private timer: ReturnType<typeof setInterval> | undefined

  constructor(options: CallSchedulerOptions) {
    this.options = options
    for (const call of options.calls) this.enabled.set(call.to, call.enabled)
  }

  setEnabled(target: string, enabled: boolean): boolean {
    if (!this.enabled.has(target)) return false
    this.enabled.set(target, enabled)
    return true
  }

  isEnabled(target: string): boolean {
    return this.enabled.get(target) === true
  }

  async tick(): Promise<void> {
    const targets = [...this.enabled].filter(([, on]) => on).map(([target]) => target)
    await Promise.all(
      targets.map((target) =>
        this.options.invoke(target).catch((error: unknown) => this.options.onError?.(target, error)),
      ),
    )
  }

  start(): void {
    this.timer = setInterval(() => void this.tick(), this.options.intervalMs)
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer)
  }
}
