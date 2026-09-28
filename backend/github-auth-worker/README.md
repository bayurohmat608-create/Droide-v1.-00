# Droide GitHub Auth Relay

This tiny Cloudflare Worker is the confidential exchange half of Droide's native GitHub user authorization + PKCE flow. The Android APK contains only the public GitHub client ID. `GITHUB_CLIENT_SECRET` stays in the Worker secret store and is used only to exchange an already user-authorized code.

For production, prefer a **GitHub App** rather than a legacy OAuth App. GitHub recommends GitHub Apps for finer-grained permissions and short-lived user tokens, while the browser authorization-code flow and PKCE use the same `https://github.com/login/oauth/authorize` / `https://github.com/login/oauth/access_token` family used by this relay.


## Required GitHub App permissions for Droide Git transport

Droide's connected-account token is also the credential source for HTTPS `github.com` clone/fetch/pull/push. Configure the production GitHub App with the minimum repository permissions required by that workflow:

- **Contents: Read & write** for ordinary repository Git read/write access.
- **Workflows: Read & write** only because Droide is a full IDE and must be able to push edits under `.github/workflows`; omit this permission only if the product intentionally blocks those edits.
- Keep **expiring user access tokens enabled** so GitHub issues the rotating eight-hour access token / refresh-token pair used by Droide's lifecycle authority.
- Make the GitHub App public (or otherwise installable by the intended users) and set Android build `DROIDE_GITHUB_APP_SLUG` to the app's public slug. Droide derives only GitHub's documented public install URL `https://github.com/apps/<slug>/installations/new`; this value is not a secret.
- Users must install/grant the GitHub App on the account or organization and repositories they want Droide to reach. User authorization alone does not grant repository installation access.

GitHub documents that installation or user access tokens can authenticate HTTP Git when the app has the `Contents` repository permission, with the token used as the HTTP password. Droide never embeds that token in the remote URL; JGit receives it only through an in-memory `CredentialsProvider`, and the provider is cleared after each transport operation.

## Contract

All endpoints accept `application/x-www-form-urlencoded`, never log credentials, and return `Cache-Control: no-store`.

- `POST /oauth/github/exchange`
  - fields: `client_id`, `code`, `code_verifier`, `redirect_uri`
  - `redirect_uri` must exactly equal Worker `GITHUB_REDIRECT_URI`; local/dev defaults to `com.baystudio.droide.oauth://github/callback`
  - exchanges an already authorized PKCE code for the initial user credential
- `POST /oauth/github/refresh`
  - fields: `client_id`, `grant_type=refresh_token`, `refresh_token`
  - the app secret stays in the Worker; a successful GitHub refresh rotates both the access token and refresh token
  - Android therefore persists the newly rotated credential without ever rolling back to the old token pair
- `POST /oauth/github/revoke`
  - fields: `client_id`, `access_token`
  - the Worker uses GitHub's `DELETE /applications/{client_id}/grant` endpoint with Basic authentication
  - this revokes the application's authorization for that user and invalidates all OAuth tokens for the app/user, not just the token on the current device

Provision `GITHUB_CLIENT_ID` as a normal Worker variable and `GITHUB_CLIENT_SECRET` as a Worker secret. The example Wrangler config declares `GITHUB_CLIENT_SECRET` under `[secrets].required`, so current Wrangler can fail deployment when the secret binding is missing. Build Android with the same client ID in `DROIDE_GITHUB_ACCOUNT_CLIENT_ID` and this Worker endpoint in `DROIDE_GITHUB_ACCOUNT_EXCHANGE_URL`.

The callback is protected by an unguessable `state`, PKCE S256, exact callback-route validation, and one-shot attempt ownership. Local/dev builds default to the Droide custom scheme. Production source already supports switching the exact callback to a verified HTTPS Android App Link through `DROIDE_GITHUB_ACCOUNT_REDIRECT_URI` / `GITHUB_REDIRECT_URI`; the final BayStudio auth domain, GitHub App callback configuration, `assetlinks.json`, and Play release-signing fingerprint still have to be provisioned externally. Keep wildcard callback matching disabled.

A public/native client can still be impersonated by another app that reuses its public client ID. Keeping the secret in this relay prevents secret extraction from the APK, but it does not turn the native app into a confidential client. If backend abuse prevention later needs stronger device provenance, bind the relay to an explicit app-attestation policy rather than treating possession of the client ID as proof of Droide.

For production abuse containment, add a Cloudflare Rate Limiting binding in the deployment environment rather than hard-coding an account-specific namespace into this source template. Do not rely only on mobile IP addresses as the identity key: Cloudflare explicitly notes that IPs can be shared by many legitimate users. Rate limiting is defense-in-depth for the relay/upstream budget, not an OAuth authorization control.

## Credential lifecycle

GitHub recommends expiring GitHub App user tokens. With expiration enabled, user access tokens expire after eight hours and refresh tokens after six months. Refresh is rotating: after a refresh succeeds, the old refresh token and old access token no longer work. Droide refreshes only near expiry, serializes exchange/refresh/revoke through one credential network lane, revalidates the GitHub user identity, and claims an exact-generation commit fence immediately before durable storage. A rotated credential is never rolled back to the old pair.

Normal **Disconnect GitHub** uses the application-grant revoke endpoint, not GitHub's credential-leak revocation API. If the remote revoke fails transiently, Droide keeps the local usable credential so the user can retry. If GitHub has already accepted the revoke but local cleanup is incomplete, Droide treats that credential as remotely dead and reports the cleanup problem separately. If the local access token is already expired and cannot be refreshed, the app can only remove its unusable local credential; it must not claim that a remote grant was revoked.

## References

- GitHub App best practices: https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/best-practices-for-creating-a-github-app
- GitHub App expiring user-token refresh: https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/refreshing-user-access-tokens
- GitHub application grant revoke: https://docs.github.com/en/rest/apps/oauth-applications#delete-an-app-authorization
- GitHub OAuth authorization + PKCE: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
- GitHub App callback URLs: https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/about-the-user-authorization-callback-url
- Android App Links: https://developer.android.com/training/app-links/about
- Cloudflare Workers secrets: https://developers.cloudflare.com/workers/configuration/secrets/
- Cloudflare Workers rate limiting: https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/

### Production callback / Android App Link

Set the same exact callback in both build and Worker:

- Android/Gradle: `DROIDE_GITHUB_ACCOUNT_REDIRECT_URI=https://<owned-host>/<callback-path>`
- Worker `[vars]`: `GITHUB_REDIRECT_URI=https://<owned-host>/<callback-path>`
- GitHub App callback URL: the same HTTPS URL

For production, the HTTPS host must be owned by BayStudio and associated with `com.baystudio.droide` using Android Digital Asset Links (`https://<owned-host>/.well-known/assetlinks.json`) containing the **release signing certificate SHA-256 fingerprint**. Do not copy a debug fingerprint into production. The manifest is generated with `android:autoVerify=true` when the configured callback uses HTTPS. The Worker pins one configured redirect and never accepts an arbitrary redirect supplied by the client.
