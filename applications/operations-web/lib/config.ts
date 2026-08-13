export const config = {
  keycloakIssuer: process.env.KEYCLOAK_ISSUER ?? "http://localhost:8180/realms/ledgerops",
  keycloakClientId: process.env.KEYCLOAK_CLIENT_ID ?? "operations-web",
  redirectUri: process.env.KEYCLOAK_REDIRECT_URI ?? "http://localhost:3001/api/auth/callback",
  redisUrl: process.env.REDIS_URL ?? "redis://localhost:6379",
  coreBaseUrl: process.env.CORE_BASE_URL ?? "http://localhost:8080",
  bffOrigin: process.env.BFF_ORIGIN ?? "http://localhost:3001",
  sessionTtlSeconds: Number(process.env.SESSION_TTL_SECONDS ?? "3600"),
  sessionRefreshLeewaySeconds: Number(process.env.SESSION_REFRESH_LEEWAY_SECONDS ?? "30"),
};

export const oidcEndpoints = {
  authorization: `${config.keycloakIssuer}/protocol/openid-connect/auth`,
  token: `${config.keycloakIssuer}/protocol/openid-connect/token`,
  jwks: `${config.keycloakIssuer}/protocol/openid-connect/certs`,
};
