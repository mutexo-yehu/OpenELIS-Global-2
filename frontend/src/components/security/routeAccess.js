import { matchPath } from "react-router-dom";
import { Roles } from "../utils/Utils";
import { buildRoutes } from "./routeAccess.generated";
import { accessAllowed } from "./accessAllowed";

let appRoutes;

/**
 * App.jsx's routes and guards, built on first use. If they can't be built
 * (e.g. a test mocks Utils without Roles), nothing is hidden: the pages still
 * guard themselves.
 */
function appRouteTable() {
  if (appRoutes === undefined) {
    try {
      appRoutes = buildRoutes();
    } catch (error) {
      console.error("Route access table unavailable; menu not filtered", error);
      appRoutes = null;
    }
  }
  return appRoutes;
}

/**
 * Roles whose work starts from a patient, and so see the header's patient
 * search. Others (e.g. Reports or Audit Trail only) don't.
 */
export function patientSearchRoles() {
  return [
    Roles.GLOBAL_ADMIN,
    Roles.RECEPTION,
    Roles.RESULTS,
    Roles.VALIDATION,
    Roles.PATHOLOGIST,
    Roles.CYTOPATHOLOGIST,
  ];
}

export function canSearchPatients(userDetails) {
  let roles;
  try {
    roles = patientSearchRoles();
  } catch (error) {
    return true; // Roles unavailable (mocked): show the search, as before
  }
  return accessAllowed({ role: roles }, userDetails);
}

/**
 * Whether the user may open the page at `url`, judged by the guard App.jsx puts
 * on that route (see routeAccess.generated.js). Mirrors <Switch>: the first
 * route that matches decides. A URL no route matches (an external link, a legacy
 * page served by the backend) is left to the server: true.
 */
export function canOpen(url, userDetails, routes = appRouteTable()) {
  if (!routes || !url || !url.startsWith("/") || url.startsWith("//")) {
    return true;
  }
  const pathname = url.split(/[?#]/)[0];
  for (const route of routes) {
    const paths = [].concat(route.path);
    if (
      paths.some((path) => matchPath(pathname, { path, exact: route.exact }))
    ) {
      return !route.guarded || accessAllowed(route, userDetails);
    }
  }
  return true;
}

/**
 * The menu tree without the pages the user can't open, and without menus left
 * empty by that. Sections and headings follow their children.
 */
export function filterMenusByAccess(
  items,
  userDetails,
  routes = appRouteTable(),
) {
  if (!routes) return items || [];
  return (items || []).flatMap((item) => {
    const children = item.childMenus || [];
    if (children.length) {
      const visibleChildren = filterMenusByAccess(
        children,
        userDetails,
        routes,
      );
      return visibleChildren.length
        ? [{ ...item, childMenus: visibleChildren }]
        : [];
    }
    return canOpen(item.menu?.actionURL, userDetails, routes) ? [item] : [];
  });
}
