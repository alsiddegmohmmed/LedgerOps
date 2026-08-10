import nextVitals from "eslint-config-next/core-web-vitals";

const config = [...nextVitals];

const finalConfig = [
  {
    ignores: [".next/**", ".next-e2e/**", "playwright-report/**", "test-results/**"],
  },
  ...config,
];

export default finalConfig;
