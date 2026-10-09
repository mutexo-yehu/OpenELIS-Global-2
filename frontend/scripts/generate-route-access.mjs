// Generates src/components/security/routeAccess.generated.js: every <Route> and
// <SecureRoute> in src/App.jsx, in <Switch> order, with the guard props
// (role, permission, labUnitRole) copied as written. The side menu uses it to
// hide pages the user would be refused (see routeAccess.js).
//
//   node scripts/generate-route-access.mjs          write the file
//   node scripts/generate-route-access.mjs --check  fail if it is out of date
//
// scripts/generate-route-access.test.js fails when App.jsx changes and the file
// was not regenerated.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import prettier from "prettier";
import ts from "typescript";

const frontendDirectory = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
export const APP_PATH = path.join(frontendDirectory, "src", "App.jsx");
export const OUTPUT_PATH = path.join(
  frontendDirectory,
  "src",
  "components",
  "security",
  "routeAccess.generated.js",
);

const ROUTE_TAGS = new Set(["Route", "SecureRoute"]);
const GUARD_PROPS = ["role", "permission", "labUnitRole"];

/** Routes in the order <Switch> would try them: nested routes before their parent. */
export function collectRoutes(source) {
  const file = ts.createSourceFile(
    "App.jsx",
    source,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.JSX,
  );
  const text = (node) => node.getText(file);
  const routes = [];

  // names a copied expression can use: imports and App.jsx's top-level consts
  const known = new Set();
  for (const statement of file.statements) {
    if (ts.isImportDeclaration(statement) && statement.importClause) {
      const clause = statement.importClause;
      if (clause.name) known.add(clause.name.text);
      if (clause.namedBindings && ts.isNamedImports(clause.namedBindings)) {
        clause.namedBindings.elements.forEach((e) => known.add(e.name.text));
      }
    } else if (ts.isVariableStatement(statement)) {
      statement.declarationList.declarations.forEach((d) =>
        known.add(d.name.getText(file)),
      );
    }
  }

  // a path built in a loop (`/qa/qi/${slug}`) becomes a route pattern (/qa/qi/:slug)
  const pathText = (node) => {
    if (!node || !ts.isTemplateExpression(node)) return null;
    let result = node.head.text;
    for (const span of node.templateSpans) {
      const expression = span.expression;
      if (ts.isIdentifier(expression) && !known.has(expression.text)) {
        result += `:${expression.text}`;
      } else {
        return null;
      }
      result += span.literal.text;
    }
    return JSON.stringify(result);
  };

  const attributesOf = (opening) => {
    const attributes = {};
    for (const attribute of opening.attributes.properties) {
      if (!ts.isJsxAttribute(attribute)) continue;
      const name = attribute.name.getText(file);
      const init = attribute.initializer;
      if (!init) attributes[name] = { value: "true", node: null };
      else if (ts.isStringLiteral(init))
        attributes[name] = { value: JSON.stringify(init.text), node: init };
      else if (ts.isJsxExpression(init) && init.expression)
        attributes[name] = {
          value: text(init.expression),
          node: init.expression,
        };
    }
    return attributes;
  };

  const visit = (node, parentPath) => {
    const opening = ts.isJsxElement(node)
      ? node.openingElement
      : ts.isJsxSelfClosingElement(node)
        ? node
        : null;
    if (opening && ROUTE_TAGS.has(opening.tagName.getText(file))) {
      const attributes = attributesOf(opening);
      const pattern = pathText(attributes.path?.node);
      let routePath = pattern || attributes.path?.value;
      if (routePath && parentPath) {
        // nested routes build their path from the parent's: match.path
        routePath = routePath.replace(/\bmatch\.path\b/g, parentPath);
        // `${"/order/clinical"}/enter` -> "/order/clinical/enter"
        routePath = routePath.replace(
          /^`\$\{"([^"`$]*)"\}([^`$]*)`$/,
          (_, base, rest) => JSON.stringify(base + rest),
        );
      }
      const nested = [];
      ts.forEachChild(node, (child) =>
        nested.push(...visitAll(child, routePath || parentPath)),
      );
      routes.push(...nested);
      if (routePath) {
        const route = {
          path: routePath,
          exact: attributes.exact?.value ?? "false",
          guarded: opening.tagName.getText(file) === "SecureRoute",
          guards: {},
          nodes: pattern ? [] : [attributes.path.node],
        };
        for (const prop of GUARD_PROPS) {
          if (attributes[prop]) {
            route.guards[prop] = attributes[prop].value;
            route.nodes.push(attributes[prop].node);
          }
        }
        routes.push(route);
      }
      return true;
    }
    return false;
  };

  const visitAll = (node, parentPath) => {
    const before = routes.length;
    const collected = [];
    const walk = (current) => {
      if (!visit(current, parentPath)) ts.forEachChild(current, walk);
    };
    walk(node);
    collected.push(...routes.splice(before));
    return collected;
  };

  routes.push(...visitAll(file, null));
  return { file, routes };
}

/** Identifiers the copied expressions need, with the imports App.jsx uses for them. */
function importsFor(file, routes) {
  const needed = new Set();
  const collect = (node) => {
    if (!node) return;
    if (ts.isIdentifier(node)) needed.add(node.text);
    else if (ts.isPropertyAccessExpression(node)) collect(node.expression);
    else if (ts.isPropertyAssignment(node)) collect(node.initializer);
    else ts.forEachChild(node, collect);
  };
  routes.forEach((route) => route.nodes.forEach(collect));
  needed.delete("match");
  needed.delete("undefined");

  // App.jsx's own top-level consts are copied, not imported (importing App.jsx
  // would load the whole app); what they use is imported in turn
  const locals = [];
  const copied = new Set();
  let added = true;
  while (added) {
    added = false;
    for (const statement of file.statements) {
      if (!ts.isVariableStatement(statement)) continue;
      for (const declaration of statement.declarationList.declarations) {
        const name = declaration.name.getText(file);
        if (needed.has(name) && !copied.has(name)) {
          copied.add(name);
          needed.delete(name);
          locals.push(
            `const ${name} = ${declaration.initializer.getText(file)};`,
          );
          collect(declaration.initializer);
          needed.delete(name);
          added = true;
        }
      }
    }
    copied.forEach((name) => needed.delete(name));
  }

  const imports = new Map(); // module -> {defaultName, names}
  for (const statement of file.statements) {
    if (!ts.isImportDeclaration(statement) || !statement.importClause) continue;
    const module = statement.moduleSpecifier.text;
    const clause = statement.importClause;
    const entry = imports.get(module) || { defaultName: null, names: [] };
    if (clause.name && needed.has(clause.name.text)) {
      entry.defaultName = clause.name.text;
      needed.delete(clause.name.text);
    }
    if (clause.namedBindings && ts.isNamedImports(clause.namedBindings)) {
      for (const element of clause.namedBindings.elements) {
        if (needed.has(element.name.text)) {
          entry.names.push(
            element.propertyName
              ? `${element.propertyName.text} as ${element.name.text}`
              : element.name.text,
          );
          needed.delete(element.name.text);
        }
      }
    }
    if (entry.defaultName || entry.names.length) imports.set(module, entry);
  }
  if (needed.size) {
    throw new Error(
      `Route guards in App.jsx use values it doesn't import (declare them in a module so ` +
        `routeAccess.generated.js can import them too): ${[...needed].join(", ")}`,
    );
  }

  const fromDirectory = path.dirname(OUTPUT_PATH);
  const appDirectory = path.dirname(APP_PATH);
  const importLines = [...imports.entries()].map(
    ([module, { defaultName, names }]) => {
      let specifier = module;
      if (module.startsWith(".")) {
        specifier = path
          .relative(fromDirectory, path.resolve(appDirectory, module))
          .split(path.sep)
          .join("/");
        if (!specifier.startsWith(".")) specifier = "./" + specifier;
      }
      const parts = [
        defaultName,
        names.length ? `{ ${names.join(", ")} }` : null,
      ];
      return `import ${parts.filter(Boolean).join(", ")} from "${specifier}";`;
    },
  );
  return { importLines, locals: locals.reverse() };
}

export async function generateRouteAccess(source) {
  const { file, routes } = collectRoutes(source);
  const entries = routes.map((route) => {
    const fields = [
      `path: ${route.path}`,
      `exact: ${route.exact}`,
      `guarded: ${route.guarded}`,
      ...Object.entries(route.guards).map(
        ([prop, value]) => `${prop}: ${value}`,
      ),
    ];
    return `  { ${fields.join(", ")} },`;
  });
  const { importLines, locals } = importsFor(file, routes);
  const code = [
    "// Generated from src/App.jsx by scripts/generate-route-access.mjs. Do not edit:",
    "// run `node scripts/generate-route-access.mjs` after changing routes in App.jsx.",
    ...importLines,
    "",
    "/**",
    " * App.jsx's routes in <Switch> order, with the guards SecureRoute enforces.",
    " * Built on call, not on import, so modules that are mocked in tests aren't read early.",
    " */",
    "export function buildRoutes() {",
    ...locals,
    "  return [",
    ...entries,
    "  ];",
    "}",
    "",
  ].join("\n");
  const options = (await prettier.resolveConfig(OUTPUT_PATH)) || {};
  return prettier.format(code, { ...options, filepath: OUTPUT_PATH });
}

if (
  process.argv[1] &&
  fileURLToPath(import.meta.url) === path.resolve(process.argv[1])
) {
  const generated = await generateRouteAccess(
    fs.readFileSync(APP_PATH, "utf8"),
  );
  if (process.argv.includes("--check")) {
    const current = fs.existsSync(OUTPUT_PATH)
      ? fs.readFileSync(OUTPUT_PATH, "utf8")
      : "";
    if (current !== generated) {
      console.error(
        "routeAccess.generated.js is out of date: run node scripts/generate-route-access.mjs",
      );
      process.exit(1);
    }
  } else {
    fs.writeFileSync(OUTPUT_PATH, generated);
    console.log(`Wrote ${path.relative(frontendDirectory, OUTPUT_PATH)}`);
  }
}
