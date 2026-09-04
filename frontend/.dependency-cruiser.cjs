/**
 * dependency-cruiser configuration — frontend only.
 *
 * Scope note that matters more than any rule below: this tool reads JavaScript and
 * TypeScript. The backend is Java, so **nothing here says anything about it**. A pair of
 * areas that this config reports as unconnected may be tightly coupled across the REST
 * boundary — `shared/models/analysis.models.ts` is a hand-written mirror of the Java
 * records and no import edge exists to prove it. Absence of an edge here is `unknown`,
 * never "no coupling". See `context/map/artifact-2-structure.md`.
 *
 * The rules encode the layering the app already has, so a violation is news:
 *   environments, shared/models  (foundation — types and constants, no behaviour)
 *     -> core/services           (HTTP, DI)
 *       -> features/analyzer     (the host component)
 *         -> features/analyzer/components/*  (leaf panels)
 */
module.exports = {
  forbidden: [
    {
      name: 'no-circular',
      severity: 'error',
      comment:
        'A cycle means neither module can be read, tested, or replaced without the other.',
      from: {},
      to: { circular: true },
    },
    {
      name: 'no-orphans',
      severity: 'warn',
      comment:
        'Nothing imports it and it imports nothing — either dead, or reached only by a template ' +
        '(Angular HTML is invisible to this tool, so check before deleting).',
      from: {
        orphan: true,
        pathNot: [
          '(^|/)[.][^/]+[.](?:js|cjs|mjs|ts|cts|mts|json)$',
          '[.]d[.]ts$',
          '(^|/)tsconfig[.]json$',
          '(^|/)(?:babel|webpack)[.]config[.](?:js|cjs|mjs|ts|json)$',
          '^src/main[.]ts$',
          '^src/test[.]ts$',
        ],
      },
      to: {},
    },
    {
      name: 'models-are-a-foundation',
      severity: 'error',
      comment:
        'shared/models is the contract mirror. If it imports a service or a component it stops ' +
        'being a type declaration and starts being behaviour, and the mirror gets harder to diff ' +
        'against the Java records.',
      from: { path: '^src/app/shared/' },
      to: { path: '^src/app/(core|features)/' },
    },
    {
      name: 'core-must-not-know-features',
      severity: 'error',
      comment:
        'A service reaching into a feature inverts the dependency and makes the service ' +
        'untestable without the component.',
      from: { path: '^src/app/core/' },
      to: { path: '^src/app/features/' },
    },
    {
      name: 'panels-do-not-import-each-other',
      severity: 'error',
      comment:
        'Leaf panels compose through their host component, not sideways. A lateral import ' +
        'between two panels means a change to one silently re-renders the other.',
      from: { path: '^src/app/features/analyzer/components/([^/]+)/' },
      to: {
        path: '^src/app/features/analyzer/components/([^/]+)/',
        pathNot: '^src/app/features/analyzer/components/$1/',
      },
    },
    {
      name: 'no-deprecated-core',
      severity: 'warn',
      comment: 'Node core modules deprecated by Node itself.',
      from: {},
      to: { dependencyTypes: ['core'], path: '^(punycode|domain|sys|querystring)$' },
    },
    {
      name: 'not-to-dev-dep',
      severity: 'error',
      comment:
        'Shipping code importing a devDependency builds locally and fails in the Cloudflare ' +
        'Pages build, which installs with the production tree.',
      from: { path: '^src/', pathNot: '[.]spec[.]ts$' },
      to: { dependencyTypes: ['npm-dev'] },
    },
  ],
  options: {
    doNotFollow: { path: 'node_modules' },
    tsPreCompilationDeps: true,
    tsConfig: { fileName: 'tsconfig.json' },
    exclude: { path: '(^|/)node_modules/' },
    reporterOptions: {
      dot: { collapsePattern: 'node_modules/(?:@[^/]+/[^/]+|[^/]+)' },
      archi: {
        collapsePattern:
          '^(?:src/app/(?:core/services|shared/models|features/analyzer/components/[^/]+|features/analyzer))|^src/environments',
      },
    },
  },
};
