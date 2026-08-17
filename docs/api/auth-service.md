# Authentication API

Base path: `/api/v1/auth`. All payloads are JSON.

| Endpoint | Result |
|---|---|
| `POST /register` | `201` and an access/refresh token pair; `409` for duplicate email |
| `POST /login` | `200`; `401` uses the same message for unknown email and wrong password |
| `POST /refresh` | `200` and rotates the refresh token; `401` for invalid, expired, or revoked tokens |
| `POST /logout` | `204`, including for an unknown token |

Example registration: `{"email":"customer@example.test","password":"SecurePassword123"}`. Refresh and logout accept `{"refreshToken":"<token>"}`. Health is at `/actuator/health`; development OpenAPI is at `/swagger-ui/index.html`.
