Status: done

## Parent

`.scratch/1.62-auth-route-guards/PRD.md` — Task 1.62: Auth route guards + sign out

## What to build

Set up the Vitest test infrastructure for the frontend so that unit tests can be written and run. This is the foundation that all other test slices in 1.62 depend on.

- Create `vitest.config.ts` at the frontend root with `environment: 'happy-dom'` and a `setupFiles` entry pointing to `src/test/setup.ts`
- Create `src/test/setup.ts` that imports `@testing-library/jest-dom/vitest` to extend Vitest with DOM matchers
- Add a `"test": "vitest"` script to `package.json` alongside the existing `"typecheck"` script
- Verify the config is valid by running `vitest --run` with no test files (exits 0 with "no test files found")

## Acceptance criteria

- [ ] `frontend/vitest.config.ts` exists with `environment: 'happy-dom'` and `setupFiles: ['src/test/setup.ts']`
- [ ] `frontend/src/test/setup.ts` exists and imports `@testing-library/jest-dom/vitest`
- [ ] `package.json` has `"test": "vitest"` in the `scripts` block
- [ ] `pnpm test --run` exits 0 (or equivalent runner command)
- [ ] No existing build or typecheck scripts are broken

## Blocked by

None — can start immediately.
