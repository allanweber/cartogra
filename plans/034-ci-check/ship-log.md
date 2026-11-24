# Ship log: plan 034

2026-11-24 · round 1 · execute · DONE. T1–T18, T20 done; T19 written, not run (k3d is the k3d-verify step). Final checks pass: gradle build, frontend lint/typecheck/test/build, seed typecheck/test, e2e 10/10. 5 plan changes (402 limit, no tenant FK, BR-9 bucket numbers, CheckServiceNotFoundException, v1 spec for CI check, S-21 admin-only UI). Open: T19, AC-10 (k3d), AC-11 (review).
2026-11-24 · round 1 · security-audit decision · yes: auth services/gateway/.../config/SecurityConfig.java, queries services/contract/.../infrastructure/jdbc/JdbcServiceProjectionRepository.java, config infra/helm/local-test.sh
2026-11-24 · round 1 · review-plan · CHANGES REQUIRED, 0 blockers, 1 major (F-1 rate limit order), 4 minor; UNVERIFIED T17, S-20, S-21 (e2e not rerun by reviewer), T19, AC-10 (k3d)
2026-11-24 · round 1 · code-review · Standards 13 (3 hard), Spec 6
2026-11-24 · round 1 · security-audit · 0 critical, 0 high, 0 medium, 2 low, 3 info; security-review: no findings
2026-11-24 · round 1 · gate 3 · approved T21–T26 (all 6); none refused
2026-11-24 · round 2 · execute · DONE T21–T26; final checks pass: gradle build, frontend lint/typecheck/507 tests/build, seed 89 tests, e2e 10/10; open T19, AC-10, AC-11 (k3d)
2026-11-24 · round 2 · review-plan · CHANGES REQUIRED, 0 blockers, 1 major (R2-F1 T22 contract /check/ gives 403), 4 minor; UNVERIFIED T19, AC-10, AC-11 (k3d)
2026-11-24 · round 2 · code-review · Standards 0 hard, 5 judgement; Spec 1 (T22 end to end)
2026-11-24 · round 2 · security-audit decision · yes: auth services/gateway/.../config/SecurityConfig.java, ApiKeyAuthenticationFilter.java, RateLimitFilter.java; output docs/security-audit/2026-11-24-034-r2/
2026-11-24 · round 2 · security-audit · 0 critical, 0 high, 0 medium, 2 low (F1 shared IP pre-bucket, F2 guest list), 3 info; security-review: no findings; build_report.py cover fix (title was cut)
2026-11-24 · round 2 · gate 3 · approved F1 slash in contract, F3 RateLimiter, F4 key hygiene, F5 expiry date, F7 roadmap tick; refused F2 IP bucket on failed lookup, F6 PlanLimits ctor
2026-11-24 · round 3 · execute · DONE T27–T31 (T22 now end to end); final checks pass: gradle build, frontend lint/typecheck/509 tests/build, seed 90 tests, e2e 10/10 (guest-demo after removal of 12 old demo-* services in the local demo tenant, user decision); open T19, AC-10, AC-11 (k3d)
2026-11-24 · round 3 · review-plan · APPROVED, 0 blockers, 0 major, 2 minor (R3-F1 date tests pass in UTC with the bug, R3-F2 roadmap :184); code-review: Standards 1 hard (N1 TanStack Forms), 7 judgement; Spec 2 (roadmap :14, :184)
2026-11-24 · round 3 · security-audit decision · yes: auth services/gateway/.../config/RateLimiter.java, ApiKeyAuthenticationFilter.java, services/contract/.../config/SecurityConfig.java; output docs/security-audit/2026-11-24-034-r3/
2026-11-24 · round 3 · security-audit · 0 critical, 0 high, 0 medium, 2 low (F1 apikey-lookup fails open on Redis error, no failed-key log; F2 guest list), 1 info; security-review: no findings; round 2 F3, F4 fixed
2026-11-24 · round 3 · gate 3 · user: no more review fixes; all round 3 candidates stay open
2026-11-24 · gate 4 · user: commit, no push; 10 commits, one for each module
