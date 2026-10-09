import { hasPermission } from "../utils/Utils";

/**
 * Whether a user may open a page guarded by SecureRoute's `role`, `permission`
 * and `labUnitRole` props. SecureRoute enforces it; the side menu uses it to
 * hide pages the user would be refused, so the two can't disagree.
 */
export function accessAllowed(guard, userDetails) {
  const { role, permission, labUnitRole } = guard || {};
  // role and permission are OR'd: either grants access. With neither prop,
  // any authenticated user passes (existing behavior).
  const roleMatches = role
    ? []
        .concat(role)
        .some((r) => userDetails?.roles && userDetails.roles.includes(r))
    : !permission;
  const hasRole = roleMatches || hasPermission(userDetails, permission);
  let containsLabUnitRole = false;
  if (labUnitRole) {
    Object.keys(labUnitRole).forEach((labunit) => {
      if (userDetails?.userLabRolesMap) {
        const userRoles = userDetails.userLabRolesMap["AllLabUnits"]
          ? userDetails.userLabRolesMap["AllLabUnits"]
          : userDetails.userLabRolesMap[labunit] || [];
        labUnitRole[labunit].forEach((r) => {
          if (userRoles.includes(r)) {
            containsLabUnitRole = true;
          }
        });
      }
    });
  }
  const hasLabUnitRole = !labUnitRole || containsLabUnitRole;
  return hasRole && hasLabUnitRole;
}
