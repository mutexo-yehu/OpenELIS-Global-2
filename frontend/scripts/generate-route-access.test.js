import fs from "node:fs";
import { describe, expect, test } from "vitest";
import {
  APP_PATH,
  OUTPUT_PATH,
  collectRoutes,
  generateRouteAccess,
} from "./generate-route-access.mjs";

describe("routeAccess.generated.js", () => {
  test("is up to date with App.jsx (run node scripts/generate-route-access.mjs)", async () => {
    const generated = await generateRouteAccess(
      fs.readFileSync(APP_PATH, "utf8"),
    );
    expect(fs.readFileSync(OUTPUT_PATH, "utf8")).toBe(generated);
  }, 30_000);

  test("lists nested routes before their parent and turns loop paths into patterns", () => {
    const { routes } = collectRoutes(`
      import { Roles } from "./components/utils/Utils";
      const PAGES = [["a"], ["b"]];
      export default () => (
        <Switch>
          <Route path="/order" render={({ match }) => (
            <Switch>
              <SecureRoute path={\`\${match.path}/enter\`} exact role={Roles.RECEPTION} />
            </Switch>
          )} />
          {PAGES.map(([slug]) => (
            <SecureRoute key={slug} path={\`/qi/\${slug}\`} exact role={[Roles.RESULTS]} />
          ))}
        </Switch>
      );`);

    expect(routes.map((r) => [r.path, r.guarded, r.guards.role])).toEqual([
      ['"/order/enter"', true, "Roles.RECEPTION"],
      ['"/order"', false, undefined],
      ['"/qi/:slug"', true, "[Roles.RESULTS]"],
    ]);
  });
});
