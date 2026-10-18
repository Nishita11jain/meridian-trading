module.exports = {
  preset: 'ts-jest/presets/js-with-ts-esm',
  testEnvironment: 'node',
  roots: ['<rootDir>/src', '<rootDir>/test'],
  testMatch: ['**/*.spec.ts', '**/*.e2e-spec.ts'],
  // test/app.e2e-spec.ts is unmodified Nest boilerplate that predates this
  // service's own e2e config (package.json's test:e2e points at
  // jest.e2e.config.js, which doesn't exist yet). It fails to run under
  // this config's ESM/module setup with @nestjs/throttler, and nothing in
  // this project depends on it, so it's excluded from the default run
  // rather than deleted.
  // src/integration/ needs auth-service and trade-api-service both running, so it is
  // kept out of `npm test`; `npm run test:integration` overrides this list.
  testPathIgnorePatterns: ['<rootDir>/node_modules/', '<rootDir>/test/app.e2e-spec.ts', '<rootDir>/src/integration/'],
  moduleFileExtensions: ['ts', 'js', 'json', 'node'],
  transform: {
    '^.+\\.ts$': ['ts-jest', { useESM: true }],
  },
  extensionsToTreatAsEsm: ['.ts'],
  setupFiles: ['<rootDir>/jest.setup.cjs'],
  globals: {
    'ts-jest': {
      tsconfig: 'tsconfig.json',
      useESM: true,
    },
  },
  // A floor, not a target: coverage was ~95% when this was set, so a drop below 85% means tests were removed or skipped.
  coverageThreshold: { global: { statements: 85, branches: 75, functions: 85, lines: 85 } },
  moduleNameMapper: {
    '^(\\.{1,2}/.*)\\.js$': '$1',
  },
};
