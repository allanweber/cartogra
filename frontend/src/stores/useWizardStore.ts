import { create } from 'zustand'

interface WizardStore {
  forceShow: boolean
  openWizard: () => void
  closeWizard: () => void
}

export const useWizardStore = create<WizardStore>()((set) => ({
  forceShow: false,
  openWizard: () => set({ forceShow: true }),
  closeWizard: () => set({ forceShow: false }),
}))
