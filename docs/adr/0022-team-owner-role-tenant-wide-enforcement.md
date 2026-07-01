# TEAM_OWNER role enforced tenant-wide until team membership exists

The `TEAM_OWNER` role is semantically intended to permit editing only services owned by the user's team. However, the current data model has no user-team membership table and no team IDs in the JWT. Rather than build team membership as a side effect of the service profile editing feature, we enforce `TEAM_OWNER` as a tenant-wide privilege for now: any user with the role can edit any service in their tenant.

When a `team_members` table and team-scoped JWT claims are introduced, the authorization check on `PUT /v1/services/{id}` will be tightened to verify that the requesting user belongs to the service's owning team.

## Consequences

A `TEAM_OWNER` user can currently edit services their team does not own. This is a known and deliberate gap, not a bug.
