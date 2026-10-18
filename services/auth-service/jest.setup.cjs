// Setup test environment with required environment variables
process.env.JWT_SECRET = process.env.JWT_SECRET || '01234567890123456789012345678901';
process.env.JWT_ISSUER = process.env.JWT_ISSUER || 'auth-service-team5';
process.env.LOGIN_THROTTLE_TTL_SECONDS = process.env.LOGIN_THROTTLE_TTL_SECONDS || '900';
process.env.LOGIN_THROTTLE_LIMIT = process.env.LOGIN_THROTTLE_LIMIT || '5';
process.env.REFRESH_TOKEN_TTL_SECONDS = process.env.REFRESH_TOKEN_TTL_SECONDS || '604800';
process.env.REFRESH_TOKEN_REVOCATION_ENABLED = process.env.REFRESH_TOKEN_REVOCATION_ENABLED || 'true';
