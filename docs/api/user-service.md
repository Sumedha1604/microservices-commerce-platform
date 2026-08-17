# User Service API

Base path: `/api/v1/users/{authUserId}`.

- `POST /profile` — 201
- `GET /profile`, `PUT /profile` — 200
- `POST /addresses` — 201; `GET /addresses`, `GET /addresses/{addressId}`, `PUT /addresses/{addressId}` — 200; `DELETE /addresses/{addressId}` — 204
- `GET /preferences`, `PUT /preferences` — 200

Responses use the shared `ApiResponse`; validation errors use `ErrorResponse`.
