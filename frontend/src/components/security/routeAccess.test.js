import { describe, expect, test } from "vitest";
import { Roles } from "../utils/Utils";
import { canOpen, canSearchPatients, filterMenusByAccess } from "./routeAccess";

const user = (...roles) => ({ roles, permissions: [] });
const scientist = user(Roles.RESULTS, Roles.VALIDATION, Roles.REPORTS);
const reception = user(Roles.RECEPTION);
const admin = user(Roles.GLOBAL_ADMIN);

const menu = (elementId, actionURL, childMenus = []) => ({
  menu: { elementId, actionURL },
  childMenus,
});

describe("canOpen, with App.jsx's routes", () => {
  test("follows the role on the page's SecureRoute", () => {
    expect(canOpen("/PatientManagement", reception)).toBe(true);
    expect(canOpen("/PatientManagement", scientist)).toBe(false);
    expect(canOpen("/PatientResults/6", scientist)).toBe(false); // needs Reception
    expect(canOpen("/PatientResults", scientist)).toBe(true); // Results → By Patient
  });

  test("ignores the query string and leaves unknown and external links to the server", () => {
    expect(canOpen("/PatientResults?type=patient", scientist)).toBe(true);
    expect(canOpen("/SomeLegacyPage.do", scientist)).toBe(true);
    expect(canOpen("https://example.org/help", scientist)).toBe(true);
    expect(canOpen("", scientist)).toBe(true);
  });

  test("uses the nested route, not its unguarded parent", () => {
    expect(canOpen("/order/clinical/enter", scientist)).toBe(false);
    expect(canOpen("/order/clinical/enter", reception)).toBe(true);
  });

  test("admin-only pages", () => {
    expect(canOpen("/admin", admin)).toBe(true);
    expect(canOpen("/admin", reception)).toBe(false);
  });
});

describe("canSearchPatients", () => {
  test("roles that start from a patient see the header search", () => {
    expect(canSearchPatients(reception)).toBe(true);
    expect(canSearchPatients(scientist)).toBe(true);
    expect(canSearchPatients(admin)).toBe(true);
  });

  test("reports-only and audit-only users don't", () => {
    expect(canSearchPatients(user(Roles.REPORTS))).toBe(false);
    expect(canSearchPatients(user(Roles.AUDIT_TRAIL))).toBe(false);
    expect(canSearchPatients(undefined)).toBe(false);
  });
});

describe("filterMenusByAccess", () => {
  const routes = [
    { path: "/patient", exact: true, guarded: true, role: Roles.RECEPTION },
    { path: "/results", exact: true, guarded: true, role: Roles.RESULTS },
    { path: "/help", exact: true, guarded: false },
  ];
  const tree = [
    menu("menu_patient", "", [
      menu("menu_patient_add", "/patient"),
      menu("menu_patient_history", "/patient?history"),
    ]),
    menu("menu_results", "", [menu("menu_results_patient", "/results")]),
    menu("menu_help", "/help"),
  ];
  const ids = (items) =>
    items.map((i) => [i.menu.elementId, ids(i.childMenus || [])]);

  test("drops pages the user can't open and menus left empty", () => {
    expect(ids(filterMenusByAccess(tree, scientist, routes))).toEqual([
      ["menu_results", [["menu_results_patient", []]]],
      ["menu_help", []],
    ]);
  });

  test("keeps everything the user can open", () => {
    const both = user(Roles.RECEPTION, Roles.RESULTS);
    expect(filterMenusByAccess(tree, both, routes)).toHaveLength(3);
  });
});
